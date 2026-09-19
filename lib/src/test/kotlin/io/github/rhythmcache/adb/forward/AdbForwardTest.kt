package io.github.rhythmcache.adb.forward

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

class AdbForwardTest {

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

    @Test
    fun `parseLocalPort handles standard tcp spec`() {
        assertEquals(8080, parseLocalPort("tcp:8080"))
        assertEquals(27183, parseLocalPort("tcp:27183"))
    }

    @Test
    fun `parseLocalPort handles raw numeric string`() {
        assertEquals(9090, parseLocalPort("9090"))
    }

    @Test
    fun `parseLocalPort handles dynamic zero port`() {
        assertEquals(0, parseLocalPort("tcp:0"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseLocalPort rejects invalid port string`() {
        parseLocalPort("tcp:invalid")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseLocalPort rejects out of range port`() {
        parseLocalPort("tcp:70000")
    }

    @Test
    fun `binds server socket to localhost on free port`() {
        val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        try {
            assertTrue(serverSocket.isBound)
            assertTrue(serverSocket.localPort > 0)
            assertEquals("127.0.0.1", serverSocket.inetAddress.hostAddress)
        } finally {
            serverSocket.close()
        }
    }
}
