package io.github.rhythmcache.adb.auth

import io.github.rhythmcache.adb.AdbAuth
import io.github.rhythmcache.adb.FileKeyProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey

class AdbAuthTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `privateKeyToPem formats valid PKCS8 PEM`() {
        val keyPair = AdbAuth.generateKey()
        val pem = AdbAuth.privateKeyToPem(keyPair.private)

        assertTrue("PEM header missing", pem.startsWith("-----BEGIN PRIVATE KEY-----"))
        assertTrue("PEM footer missing", pem.trimEnd().endsWith("-----END PRIVATE KEY-----"))

        // Roundtrip: parse back from PEM
        val parsed = AdbAuth.parsePrivateKey(pem.toByteArray(Charsets.US_ASCII))
        assertNotNull("Failed to parse back private key from PEM", parsed)

        val derived = AdbAuth.deriveKeyPair(parsed!!)
        assertEquals(
            (keyPair.public as RSAPublicKey).modulus,
            (derived.public as RSAPublicKey).modulus,
        )
        assertEquals(
            (keyPair.private as RSAPrivateCrtKey).privateExponent,
            parsed.privateExponent,
        )
    }

    @Test
    fun `FileKeyProvider generates DER adbkey on disk and reads back`() = runBlocking {
        val testDir = tempFolder.newFolder("adb_keys")
        val keyFile = File(testDir, "adbkey")
        val pubKeyFile = File(testDir, "adbkey.pub")

        val provider = FileKeyProvider(keyFile, pubKeyFile, identityComment = "test@device")
        val keyPair = provider.getKeyPair()

        assertTrue(keyFile.exists())
        val savedBytes = keyFile.readBytes()
        assertEquals(0x30.toByte(), savedBytes[0]) // DER Sequence
        assertTrue(pubKeyFile.exists())

        // Read back from clean provider instance pointing to same file
        val provider2 = FileKeyProvider(keyFile, pubKeyFile)
        val keyPair2 = provider2.getKeyPair()

        assertEquals(
            (keyPair.public as RSAPublicKey).modulus,
            (keyPair2.public as RSAPublicKey).modulus,
        )
    }

    @Test
    fun `FileKeyProvider loads existing PEM adbkey from disk`() = runBlocking {
        val testDir = tempFolder.newFolder("adb_pem_keys")
        val keyFile = File(testDir, "adbkey")

        val generated = AdbAuth.generateKey()
        val pem = AdbAuth.privateKeyToPem(generated.private)
        keyFile.writeText(pem)

        val provider = FileKeyProvider(keyFile)
        val loaded = provider.getKeyPair()

        assertEquals(
            (generated.public as RSAPublicKey).modulus,
            (loaded.public as RSAPublicKey).modulus,
        )
    }
}
