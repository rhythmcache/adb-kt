package io.github.rhythmcache.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Represents an active host-side port forward rule.
 *
 * @property local The local host endpoint spec, e.g. "tcp:8080".
 * @property remote The remote device endpoint spec, e.g. "tcp:8080" or "localabstract:scrcpy".
 * @property boundPort The actual bound local TCP port on the host.
 */
data class ForwardRule(
    val local: String,
    val remote: String,
    val boundPort: Int,
)

/**
 * Manages host-to-device port forwarding rules.
 *
 * In standard ADB architecture, port forwarding is a host-side responsibility:
 * the host binds a local [ServerSocket], accepts incoming client connections, and proxies
 * raw bytes bidirectionally to a corresponding [AdbStream] opened on the target device.
 */
class AdbForward internal constructor(
    private val connection: AdbConnection,
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val activeForwards = mutableMapOf<String, ActiveForwardMapping>()

    private class ActiveForwardMapping(
        val rule: ForwardRule,
        val serverSocket: ServerSocket,
        val acceptJob: Job,
    )

    private fun parseLocalPort(local: String): Int {
        val trimmed = local.trim()
        val portStr = if (trimmed.startsWith("tcp:", ignoreCase = true)) {
            trimmed.substring(4)
        } else {
            trimmed
        }
        val port = portStr.substringAfterLast(':').toIntOrNull()
            ?: throw IllegalArgumentException("Invalid local port in '$local'. Expected format: tcp:<port>")
        require(port in 0..65535) { "Local port out of range: $port" }
        return port
    }

    /**
     * Binds a local TCP port on the host and forwards incoming connections to [remote] on the target device.
     *
     * @param local The local endpoint specification (e.g. "tcp:8080" or "tcp:0" for dynamic allocation).
     * @param remote The remote endpoint specification on the target device (e.g. "tcp:8080" or "localabstract:scrcpy").
     * @param noRebind If true, throws [AdbException.RemoteFailure] if [local] is already bound.
     */
    suspend fun add(
        local: String,
        remote: String,
        noRebind: Boolean = false,
    ) {
        val requestedPort = parseLocalPort(local)
        val requestedKey = "tcp:$requestedPort"

        mutex.withLock {
            if (noRebind && (activeForwards.containsKey(requestedKey) || activeForwards.containsKey(local))) {
                throw AdbException.RemoteFailure("cannot rebind existing socket for $local")
            }

            // Unbind any existing forward on this port if rebind is allowed
            val existing = activeForwards.remove(requestedKey) ?: activeForwards.remove(local)
            existing?.let { old ->
                old.acceptJob.cancel()
                withContext(Dispatchers.IO) {
                    runCatching { old.serverSocket.close() }
                }
            }

            val serverSocket = withContext(Dispatchers.IO) {
                ServerSocket(requestedPort, 50, InetAddress.getByName("127.0.0.1"))
            }
            val boundPort = serverSocket.localPort
            val boundKey = "tcp:$boundPort"

            val acceptJob = scope.launch {
                try {
                    while (isActive && !serverSocket.isClosed) {
                        val clientSocket = try {
                            withContext(Dispatchers.IO) { serverSocket.accept() }
                        } catch (_: Exception) {
                            break
                        }
                        launch {
                            bridgeClientSocket(clientSocket, remote)
                        }
                    }
                } finally {
                    withContext(Dispatchers.IO) {
                        runCatching { serverSocket.close() }
                    }
                }
            }

            val rule = ForwardRule(local = boundKey, remote = remote, boundPort = boundPort)
            val mapping = ActiveForwardMapping(rule, serverSocket, acceptJob)
            activeForwards[boundKey] = mapping
            if (requestedKey != boundKey) {
                activeForwards[requestedKey] = mapping
            }
        }
    }

    /**
     * Binds a local port on the host and forwards traffic to [remote] on the device using type-safe endpoints.
     */
    suspend fun add(
        local: AdbEndpoint,
        remote: AdbEndpoint,
        noRebind: Boolean = false,
    ) {
        add(local.toSpec(), remote.toSpec(), noRebind)
    }

    private suspend fun bridgeClientSocket(socket: Socket, remote: String) {
        try {
            socket.use { sock ->
                sock.tcpNoDelay = true
                val adbStream = try {
                    connection.open(remote)
                } catch (_: Exception) {
                    return
                }
                adbStream.use { stream ->
                    coroutineScope {
                        val done = CompletableDeferred<Unit>()

                        val upJob = launch(Dispatchers.IO) {
                            val buffer = ByteArray(16384)
                            val inStream = sock.getInputStream()
                            try {
                                while (isActive) {
                                    val count = inStream.read(buffer)
                                    if (count < 0) break
                                    stream.write(buffer.copyOf(count))
                                }
                            } catch (_: Exception) {
                            } finally {
                                runCatching { stream.closeWrite() }
                                done.complete(Unit)
                            }
                        }

                        val downJob = launch(Dispatchers.IO) {
                            val outStream = sock.getOutputStream()
                            try {
                                while (isActive) {
                                    val chunk = stream.recv() ?: break
                                    outStream.write(chunk)
                                    outStream.flush()
                                }
                            } catch (_: Exception) {
                            } finally {
                                done.complete(Unit)
                            }
                        }

                        done.await()
                        upJob.cancel()
                        downJob.cancel()
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    /**
     * Removes an active host port forward rule matching [local].
     */
    suspend fun remove(local: String) {
        val port = parseLocalPort(local)
        val key = "tcp:$port"
        val mapping = mutex.withLock {
            activeForwards.remove(key) ?: activeForwards.remove(local)
        } ?: return
        mapping.acceptJob.cancel()
        withContext(Dispatchers.IO) {
            runCatching { mapping.serverSocket.close() }
        }
    }

    /**
     * Removes an active host port forward rule matching [local].
     */
    suspend fun remove(local: AdbEndpoint) {
        remove(local.toSpec())
    }

    /**
     * Removes all active host port forwarding rules and closes their local sockets.
     */
    suspend fun removeAll() {
        val list = mutex.withLock {
            val copy = activeForwards.values.toSet().toList()
            activeForwards.clear()
            copy
        }
        list.forEach { mapping ->
            mapping.acceptJob.cancel()
            withContext(Dispatchers.IO) {
                runCatching { mapping.serverSocket.close() }
            }
        }
    }

    /**
     * Returns a snapshot list of all currently active host forward rules.
     */
    suspend fun list(): List<ForwardRule> = mutex.withLock {
        activeForwards.values.map { it.rule }.distinct()
    }

    /**
     * Closes the forward manager, cancelling all proxy listeners and closing active server sockets.
     */
    override fun close() {
        scope.cancel()
        val list = synchronized(activeForwards) {
            val copy = activeForwards.values.toSet().toList()
            activeForwards.clear()
            copy
        }
        list.forEach { mapping ->
            mapping.acceptJob.cancel()
            runCatching { mapping.serverSocket.close() }
        }
    }
}
