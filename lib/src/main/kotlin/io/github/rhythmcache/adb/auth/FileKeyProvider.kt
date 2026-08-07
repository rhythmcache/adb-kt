package io.github.rhythmcache.adb

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

class FileKeyProvider(
    private val keyFile: File,
    private val pubKeyFile: File? = null,
    private val identityComment: String? = null,
) : AdbKeyProvider {
    private var loadedKeyPair: KeyPair? = null

    override suspend fun getAdbPublicKeyBytes(): ByteArray? {
        if (identityComment != null) {
            val kp = getKeyPair()
            val pubKey = kp.public as RSAPublicKey
            return AdbAuth.encodePublicKeyAdb(pubKey, identityComment)
        }
        val targetPub = pubKeyFile ?: File(keyFile.parentFile ?: File("."), "${keyFile.name}.pub")
        if (targetPub.exists() && targetPub.length() > 0) {
            try {
                val text = targetPub.readText(Charsets.US_ASCII).trim()
                if (text.isNotBlank()) {
                    return if (text.endsWith(
                            "\u0000",
                        )
                    ) {
                        text.toByteArray(Charsets.US_ASCII)
                    } else {
                        "$text\u0000".toByteArray(Charsets.US_ASCII)
                    }
                }
            } catch (_: Exception) {
            }
        }
        val kp = getKeyPair()
        val pubKey = kp.public as RSAPublicKey
        return AdbAuth.encodePublicKeyAdb(pubKey)
    }

    override suspend fun getKeyPair(): KeyPair {
        loadedKeyPair?.let { return it }

        if (keyFile.exists() && keyFile.length() > 0) {
            try {
                val rawBytes = keyFile.readBytes()
                val text = String(rawBytes, Charsets.US_ASCII)
                val kf = KeyFactory.getInstance("RSA")

                val privKey: RSAPrivateCrtKey =
                    if (text.contains("-----BEGIN")) {
                        val cleanBase64 =
                            text
                                .lines()
                                .filter { !it.startsWith("-----") }
                                .joinToString("")
                                .replace("\\s".toRegex(), "")
                        val der = Base64.getDecoder().decode(cleanBase64)

                        if (text.contains("RSA PRIVATE KEY") || isPkcs1Der(der)) {
                            parsePkcs1PrivateKey(der, kf)
                        } else {
                            kf.generatePrivate(PKCS8EncodedKeySpec(der)) as RSAPrivateCrtKey
                        }
                    } else if (isPkcs1Der(rawBytes)) {
                        parsePkcs1PrivateKey(rawBytes, kf)
                    } else {
                        kf.generatePrivate(PKCS8EncodedKeySpec(rawBytes)) as RSAPrivateCrtKey
                    }

                val pubSpec = RSAPublicKeySpec(privKey.modulus, privKey.publicExponent)
                val pubKey = kf.generatePublic(pubSpec) as RSAPublicKey
                val kp = KeyPair(pubKey, privKey)
                loadedKeyPair = kp
                return kp
            } catch (e: Exception) {
                throw AdbException.Authentication("Failed to load RSA key pair from '${keyFile.absolutePath}': ${e.message}")
            }
        }

        val generated = AdbAuth.generateKey()
        try {
            keyFile.parentFile?.mkdirs()
            keyFile.writeBytes(generated.private.encoded)
            val pubBytes =
                if (identityComment != null) {
                    AdbAuth.encodePublicKeyAdb(generated.public as RSAPublicKey, identityComment)
                } else {
                    AdbAuth.encodePublicKeyAdb(generated.public as RSAPublicKey)
                }
            val targetPubFile = pubKeyFile ?: File(keyFile.parentFile, "${keyFile.name}.pub")
            targetPubFile.writeBytes(pubBytes)
        } catch (_: Exception) {
        }
        loadedKeyPair = generated
        return generated
    }

    private fun isPkcs1Der(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 0x30.toByte()) return false
        val buf = java.nio.ByteBuffer.wrap(bytes)
        buf.get() // 0x30
        readDerLength(buf)
        if (buf.remaining() < 3) return false
        if (buf.get() != 0x02.toByte()) return false // Version tag 0x02
        val versionLen = readDerLength(buf)
        if (buf.remaining() < versionLen + 1) return false
        buf.position(buf.position() + versionLen)
        return buf.hasRemaining() && buf.get() == 0x02.toByte() // Modulus tag 0x02
    }

    private fun parsePkcs1PrivateKey(
        der: ByteArray,
        kf: KeyFactory,
    ): RSAPrivateCrtKey {
        val buffer = java.nio.ByteBuffer.wrap(der)
        require(buffer.get() == 0x30.toByte()) { "Invalid DER sequence" }
        readDerLength(buffer)

        readDerInteger(buffer) // Version

        val modulus = readDerInteger(buffer)
        val publicExponent = readDerInteger(buffer)
        val privateExponent = readDerInteger(buffer)
        val prime1 = readDerInteger(buffer)
        val prime2 = readDerInteger(buffer)
        val exponent1 = readDerInteger(buffer)
        val exponent2 = readDerInteger(buffer)
        val coefficient = readDerInteger(buffer)

        val spec =
            java.security.spec.RSAPrivateCrtKeySpec(
                modulus,
                publicExponent,
                privateExponent,
                prime1,
                prime2,
                exponent1,
                exponent2,
                coefficient,
            )
        return kf.generatePrivate(spec) as RSAPrivateCrtKey
    }

    private fun readDerLength(buf: java.nio.ByteBuffer): Int {
        var len = buf.get().toInt() and 0xFF
        if (len > 0x80) {
            val count = len and 0x7F
            len = 0
            for (i in 0 until count) {
                len = (len shl 8) or (buf.get().toInt() and 0xFF)
            }
        }
        return len
    }

    private fun readDerInteger(buf: java.nio.ByteBuffer): BigInteger {
        require(buf.get() == 0x02.toByte()) { "Expected DER Integer tag 0x02" }
        val len = readDerLength(buf)
        val bytes = ByteArray(len)
        buf.get(bytes)
        return BigInteger(bytes)
    }
}
