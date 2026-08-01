package io.github.rhythmcache.adb

import io.github.rhythmcache.adb.install.AdbInstall
import io.github.rhythmcache.adb.pairing.PairingClient
import io.github.rhythmcache.adb.pairing.PairingIdentity
import io.github.rhythmcache.adb.pairing.PairingResult
import io.github.rhythmcache.adb.pairing.PeerInfoBuilder
import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import io.github.rhythmcache.adb.rescue.AdbRescue
import io.github.rhythmcache.adb.sideload.AdbSideload
import io.github.rhythmcache.adb.sync.AdbSync
import kotlinx.coroutines.flow.Flow
import java.io.Closeable
import java.security.interfaces.RSAPublicKey

class AdbClient private constructor(
    private val connection: AdbConnection,
) : Closeable {
    val sync: AdbSync = AdbSync(connection)
    val forward: AdbForward = AdbForward(connection)
    val reverse: AdbReverse = AdbReverse(connection)
    val sideload: AdbSideload = AdbSideload(connection)
    val rescue: AdbRescue = AdbRescue(connection)
    val install: AdbInstall = AdbInstall(connection)

    val deviceMode: AdbDeviceMode get() = connection.deviceMode
    val bannerString: String get() = connection.bannerString

    companion object {
        /** Connect over Plain TCP (Port 5555 or custom TCP port). */
        suspend fun connectTcp(
            host: String,
            port: Int = 5555,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            handshakeTimeoutMs: Long = 30_000,
        ): AdbClient {
            val transport = TcpPacketTransport.connect(host, port)
            val connection = AdbConnection.connect(transport, keyProvider, handshakeTimeoutMs)
            return AdbClient(connection)
        }

        /** Connect over Wireless Debugging TLS (Android 11+ TLS port). */
        suspend fun connectTls(
            host: String,
            port: Int,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            identity: PairingIdentity? = null,
            handshakeTimeoutMs: Long = 30_000,
        ): AdbClient {
            val resolvedIdentity = identity ?: buildPairingIdentity(keyProvider)
            val transport = TlsPacketTransport.connect(host, port, resolvedIdentity)
            val connection = AdbConnection.connect(transport, keyProvider, handshakeTimeoutMs)
            return AdbClient(connection)
        }

        /** Pair with a device advertising a pairing code over Wireless Debugging TLS. */
        suspend fun pairTls(
            host: String,
            port: Int,
            pairingCode: String,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            identity: PairingIdentity? = null,
            connectTimeoutMs: Int = 10_000,
        ): Result<PairingResult> {
            val resolvedIdentity = identity ?: buildPairingIdentity(keyProvider)
            val passwordBytes = pairingCode.toByteArray(Charsets.US_ASCII)
            val ourPeerInfo = PeerInfoBuilder.forOurPublicKey(resolvedIdentity.keyPair.public as RSAPublicKey)
            return PairingClient.pair(
                host = host,
                port = port,
                password = passwordBytes,
                ourPeerInfo = ourPeerInfo,
                identity = resolvedIdentity,
                connectTimeoutMs = connectTimeoutMs,
            )
        }

        /** Legacy alias for connectTcp */
        suspend fun connect(
            host: String,
            port: Int = 5555,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            handshakeTimeoutMs: Long = 30_000,
        ): AdbClient = connectTcp(host, port, keyProvider, handshakeTimeoutMs)

        /** Connect using any custom PacketTransport instance. */
        suspend fun connect(
            transport: PacketTransport,
            keyProvider: AdbKeyProvider = MemoryKeyProvider,
            handshakeTimeoutMs: Long = 30_000,
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
    fun shellFlow(cmd: String): Flow<ShellChunk> =
        kotlinx.coroutines.flow.flow {
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
