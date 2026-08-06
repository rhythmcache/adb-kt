package io.github.rhythmcache.adb

import kotlinx.coroutines.runBlocking
import org.bouncycastle.tls.TlsFatalAlert
import org.junit.Test

class TlsNotPairedTest {
    private val host = "10.249.133.73"
    private val port = 40703

    @Test
    fun attemptTlsConnectToUnpairedDevice() {
        runBlocking {
            println("Attempting TLS connect to $host:$port ...")
            try {
                val client =
                    AdbClient.connectTls(
                        host = host,
                        port = port,
                        keyProvider = MemoryKeyProvider,
                        handshakeTimeoutMs = 15_000,
                    )
                println("UNEXPECTED SUCCESS: connected. banner=${client.bannerString}")
                client.close()
            } catch (e: AdbException.NotPaired) {
                println("RESULT: Got NotPaired (expected)")
                println("message: ${e.message}")
                println("cause: ${e.cause}")
                println("cause class: ${e.cause?.javaClass?.name}")
                val alert = e.cause as? TlsFatalAlert
                println("alertDescription: ${alert?.alertDescription}")
            } catch (e: AdbException) {
                println("RESULT: Got a DIFFERENT AdbException: ${e::class.simpleName}")
                println("message: ${e.message}")
                println("cause: ${e.cause}")
                println("cause class: ${e.cause?.javaClass?.name}")
                e.cause?.printStackTrace()
            } catch (e: Exception) {
                println("RESULT: Got a non-AdbException: ${e::class.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
