package io.github.rhythmcache.adb

import kotlinx.coroutines.flow.Flow
import java.io.Closeable

class AdbClient private constructor(
    private val connection: AdbConnection
) : Closeable {
    val sync: AdbSync = AdbSync(connection)
    val forward: AdbForward = AdbForward(connection)
    val reverse: AdbReverse = AdbReverse(connection)

    companion object {
        suspend fun connect(
            host: String,
            port: Int = 5555,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            handshakeTimeoutMs: Long = 30_000
        ): AdbClient {
            val transport = TcpPacketTransport.connect(host, port)
            val connection = AdbConnection.connect(transport, keyProvider, handshakeTimeoutMs)
            return AdbClient(connection)
        }

        suspend fun connect(
            transport: PacketTransport,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            handshakeTimeoutMs: Long = 30_000
        ): AdbClient {
            val connection = AdbConnection.connect(transport, keyProvider, handshakeTimeoutMs)
            return AdbClient(connection)
        }
    }

    /** Universal escape hatch for raw streams */
    suspend fun open(service: String): AdbStream = connection.open(service)

    /** Universal escape hatch for raw streams using type-safe endpoints. */
    suspend fun open(endpoint: AdbEndpoint): AdbStream = connection.open(endpoint.toSpec())

    /** Run a shell command to completion. */
    suspend fun shell(cmd: String): ShellResult {
        val stream = connection.open("shell,v2,raw:$cmd")
        return try {
            AdbShell.collectToResult(stream)
        } finally {
            stream.close()
        }
    }

    /** Stream shell chunks live as a Flow. Guarantees closing stream on completion or cancellation. */
    fun shellFlow(cmd: String): Flow<ShellChunk> = kotlinx.coroutines.flow.flow {
        val stream = connection.open("shell,v2,raw:$cmd")
        try {
            AdbShell.flow(stream).collect { emit(it) }
        } finally {
            stream.close()
        }
    }

    val isClosed: Boolean get() = connection.isClosed

    override fun close() {
        connection.close()
    }
}
