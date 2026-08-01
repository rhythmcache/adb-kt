package io.github.rhythmcache.adb.pairing

import io.github.rhythmcache.adb.AdbCmd
import io.github.rhythmcache.adb.AdbPacket
import io.github.rhythmcache.adb.TlsPacketTransport
import io.github.rhythmcache.adb.crypto.X509Generator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okio.buffer
import okio.sink
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey

class PairingAndTransportIntegrationTest {
    @Test
    fun testFullPairingThenTlsTransportRoundTrip(): Unit =
        runBlocking {
            val keyGen = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
            val clientKeyPair = keyGen.generateKeyPair()
            val deviceKeyPair = keyGen.generateKeyPair()

            val clientIdentity =
                PairingIdentity(
                    clientKeyPair,
                    X509Generator.toPem(X509Generator.generate(clientKeyPair)),
                    X509Generator.privateKeyToPem(clientKeyPair.private),
                )
            val deviceIdentity =
                PairingIdentity(
                    deviceKeyPair,
                    X509Generator.toPem(X509Generator.generate(deviceKeyPair)),
                    X509Generator.privateKeyToPem(deviceKeyPair.private),
                )

            val password = "123456".toByteArray()

            val clientPeerInfo = PeerInfoBuilder.forOurPublicKey(clientKeyPair.public as RSAPublicKey)
            val devicePeerInfo = PeerInfo(PeerInfoType.ADB_DEVICE_GUID, "test-device-guid-1234".toByteArray())

            // Pairing over a real loopback socket pair
            val serverSocket = ServerSocket(0)
            val pairingPort = serverSocket.localPort

            val serverPairingResult =
                async(Dispatchers.IO) {
                    val socket = serverSocket.accept()
                    val res = PairingConnection.run(socket, ConnectionRole.SERVER, password, devicePeerInfo, deviceIdentity)
                    runCatching { serverSocket.close() }
                    res
                }

            val clientPairingResult =
                async(Dispatchers.IO) {
                    val socket = Socket("127.0.0.1", pairingPort)
                    PairingConnection.run(socket, ConnectionRole.CLIENT, password, clientPeerInfo, clientIdentity)
                }

            val (serverResult, clientResult) = awaitAll(serverPairingResult, clientPairingResult)

            serverResult.exceptionOrNull()?.printStackTrace()
            clientResult.exceptionOrNull()?.printStackTrace()

            assertTrue("Server-side pairing failed: ${serverResult.exceptionOrNull()}", serverResult.isSuccess)
            assertTrue("Client-side pairing failed: ${clientResult.exceptionOrNull()}", clientResult.isSuccess)

            val deviceReceivedPeerInfo = serverResult.getOrThrow().peerInfo
            val clientReceivedPeerInfo = clientResult.getOrThrow().peerInfo

            // Server should have received the client's public key
            assertEquals(PeerInfoType.ADB_RSA_PUB_KEY, deviceReceivedPeerInfo.type)
            val receivedPubKeyBytes = PeerInfoBuilder.extractPublicKeyBytes(deviceReceivedPeerInfo)
            val sentPubKeyBytes = PeerInfoBuilder.extractPublicKeyBytes(clientPeerInfo)
            assertTrue(
                "Public key bytes did not round-trip correctly through pairing",
                receivedPubKeyBytes.contentEquals(sentPubKeyBytes),
            )

            // Client should have received the device's GUID
            assertEquals(PeerInfoType.ADB_DEVICE_GUID, clientReceivedPeerInfo.type)
            val guid = PeerInfoBuilder.extractDeviceGuid(clientReceivedPeerInfo)
            assertEquals("test-device-guid-1234", guid)

            // Now pin the device's pairing cert and do a real TLS
            // transport connection + a minimal CNXN packet round trip
            val transportServerSocket = ServerSocket(0)
            val transportPort = transportServerSocket.localPort

            val serverTransportJob =
                async(Dispatchers.IO) {
                    runDeviceSideTransportEcho(transportServerSocket, deviceIdentity)
                }

            // learned during pairing, in real usage
            val transport =
                TlsPacketTransport.connect(
                    host = "127.0.0.1",
                    port = transportPort,
                    identity = clientIdentity,
                )

            // Send a CNXN packet and read back an echoed CNXN -- confirms the
            // full send()/recv() path (AdbPacket framing + checksum) works
            // correctly over the BC TLS stream, not just that the handshake
            // completed.
            val banner = "host::features=shell_v2\u0000".toByteArray()
            transport.send(AdbPacket(AdbCmd.CNXN, 0x01000001, 1024 * 1024, banner))

            val echoed = transport.recv()
            assertEquals(AdbCmd.CNXN, echoed.command)
            assertEquals(
                "Echoed CNXN payload did not match what was sent -- packet framing/checksum mismatch over TLS",
                String(banner),
                String(echoed.payload),
            )

            transport.close()
            serverTransportJob.await()
        }

    /**
     * Minimal device-side stand-in: accepts one TLS connection with the same
     * BC setup as TlsPacketTransport's client side (mirrored, not reused,
     * since this test plays the role of adbd, not the library), reads one
     * AdbPacket and echoes it back unchanged. Good enough to prove the
     * client-side send/recv path is correct; not a real adbd stand-in.
     */
    private suspend fun runDeviceSideTransportEcho(
        serverSocket: ServerSocket,
        deviceIdentity: PairingIdentity,
    ) {
        val socket = serverSocket.accept()

        val crypto: org.bouncycastle.tls.crypto.TlsCrypto =
            org.bouncycastle.tls.crypto.impl.bc
                .BcTlsCrypto(java.security.SecureRandom())

        val certHolder =
            org.bouncycastle.openssl
                .PEMParser(java.io.StringReader(deviceIdentity.certPem))
                .readObject()
                as org.bouncycastle.cert.X509CertificateHolder
        val asn1Cert =
            org.bouncycastle.asn1.x509.Certificate
                .getInstance(certHolder.encoded)
        val tlsCert = crypto.createCertificate(asn1Cert.encoded)
        val certChain = org.bouncycastle.tls.Certificate(ByteArray(0), arrayOf(org.bouncycastle.tls.CertificateEntry(tlsCert, null)))

        val keyObj =
            org.bouncycastle.openssl
                .PEMParser(java.io.StringReader(deviceIdentity.privateKeyPem))
                .readObject()
        val privKey =
            when (keyObj) {
                is org.bouncycastle.asn1.pkcs.PrivateKeyInfo ->
                    org.bouncycastle.crypto.util.PrivateKeyFactory
                        .createKey(keyObj)
                is org.bouncycastle.openssl.PEMKeyPair ->
                    org.bouncycastle.crypto.util.PrivateKeyFactory
                        .createKey(keyObj.privateKeyInfo)
                else -> error("bad key")
            }

        var capturedContext: org.bouncycastle.tls.TlsServerContext? = null
        val server =
            object : org.bouncycastle.tls.DefaultTlsServer(crypto) {
                override fun getSupportedVersions(): Array<org.bouncycastle.tls.ProtocolVersion> =
                    arrayOf(org.bouncycastle.tls.ProtocolVersion.TLSv13)

                override fun init(context: org.bouncycastle.tls.TlsServerContext) {
                    capturedContext = context
                    super.init(context)
                }

                override fun getCertificateRequest(): org.bouncycastle.tls.CertificateRequest? = null

                override fun getCredentials(): org.bouncycastle.tls.TlsCredentials =
                    org.bouncycastle.tls.crypto.impl.bc.BcDefaultTlsCredentialedSigner(
                        org.bouncycastle.tls.crypto
                            .TlsCryptoParameters(requireNotNull(capturedContext)),
                        crypto as org.bouncycastle.tls.crypto.impl.bc.BcTlsCrypto,
                        privKey,
                        certChain,
                        org.bouncycastle.tls.SignatureAndHashAlgorithm.rsa_pss_rsae_sha256,
                    )
            }

        val rawInput = socket.getInputStream()
        val rawOutput = socket.getOutputStream()

        // 1. Read initial plaintext A_CNXN header + payload from client
        val cnxnHeader = ByteArray(24)
        var read = 0
        while (read < 24) {
            val r = rawInput.read(cnxnHeader, read, 24 - read)
            if (r == -1) break
            read += r
        }
        val payloadLen =
            (cnxnHeader[12].toInt() and 0xFF) or
                ((cnxnHeader[13].toInt() and 0xFF) shl 8) or
                ((cnxnHeader[14].toInt() and 0xFF) shl 16) or
                ((cnxnHeader[15].toInt() and 0xFF) shl 24)
        if (payloadLen > 0) {
            val payload = ByteArray(payloadLen)
            var pRead = 0
            while (pRead < payloadLen) {
                val r = rawInput.read(payload, pRead, payloadLen - pRead)
                if (r == -1) break
                pRead += r
            }
        }

        // 2. Send device A_STLS header to client
        val stlsPacket = AdbPacket(AdbCmd.STLS, 0x01000000, 0, ByteArray(0))
        val stlsBuffer = okio.Buffer()
        stlsPacket.writeTo(stlsBuffer)
        rawOutput.write(stlsBuffer.readByteArray())
        rawOutput.flush()

        // 3. Read client echoed A_STLS header
        val clientStlsHeader = ByteArray(24)
        var stlsRead = 0
        while (stlsRead < 24) {
            val r = rawInput.read(clientStlsHeader, stlsRead, 24 - stlsRead)
            if (r == -1) break
            stlsRead += r
        }

        val protocol = org.bouncycastle.tls.TlsServerProtocol(rawInput, rawOutput)
        protocol.accept(server)

        val source = protocol.inputStream.source().buffer()
        val sink = protocol.outputStream.sink().buffer()

        val pkt = AdbPacket.readFrom(source)
        pkt.writeTo(sink)

        runCatching { protocol.close() }
        runCatching { socket.close() }
        runCatching { serverSocket.close() }
    }
}
