package io.github.rhythmcache.adb

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

object AdbAuth {
    /** Generate 2048-bit RSA keypair. */
    fun generateKey(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        return kpg.generateKeyPair()
    }

    /** Signs the 20 byte SHA1 token adbd sends during AUTH. */
    fun signToken(
        privateKey: PrivateKey,
        token: ByteArray,
    ): ByteArray {
        val sha1DigestInfoPrefix =
            byteArrayOf(
                0x30,
                0x21,
                0x30,
                0x09,
                0x06,
                0x05,
                0x2b,
                0x0e,
                0x03,
                0x02,
                0x1a,
                0x05,
                0x00,
                0x04,
                0x14,
            )
        val digestInfo = sha1DigestInfoPrefix + token
        val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, privateKey)
        return cipher.doFinal(digestInfo)
    }

    /**
     * Encodes an RSA public key into the raw binary ADB mincrypt structure
     * (modulus_size_words, n0inv, modulus, RR, exponent all little endian).
     * This is the cryptographic identity of the key: no comment, no username,
     * fully deterministic given the same key. Use this (not [encodePublicKeyAdb])
     * whenever you need something stable to hash, compare, or fingerprint.
     */
    fun encodePublicKeyBlob(publicKey: RSAPublicKey): ByteArray {
        val words = 64
        val n = publicKey.modulus
        val e = publicKey.publicExponent

        var eVal = e.toLong().toInt()
        if (eVal == 0) eVal = 65537

        val nWords = biguintToLeU32Words(n, words)
        val n0inv = computeN0Inv(n)

        val rSquared = computeRSquared(n, words)
        val rrWords = biguintToLeU32Words(rSquared, words)

        val buf = java.io.ByteArrayOutputStream()
        writeLeU32(buf, words)
        writeLeU32(buf, n0inv)
        for (w in nWords) writeLeU32(buf, w)
        for (w in rrWords) writeLeU32(buf, w)
        writeLeU32(buf, eVal)

        return buf.toByteArray()
    }

    /**
     * Encodes RSA public key in the format adb actually writes to adbkey.pub
     * and sends over the wire: Base64(mincrypt blob) + " " + user@host + NUL.
     * The trailing "user@host" is a display comment only .... it is not part of
     * the key's identity and differs between machines/regenerations. Do not
     * hash this output expecting a stable fingerprint; use
     * [encodePublicKeyBlob] for that instead.
     */
    fun encodePublicKeyAdb(
        publicKey: RSAPublicKey,
        identityComment: String = "${System.getProperty("user.name") ?: "user"}@adb_kt",
    ): ByteArray {
        val blob = encodePublicKeyBlob(publicKey)
        val b64 = Base64.getEncoder().encodeToString(blob)
        val comment = identityComment.ifBlank { "${System.getProperty("user.name") ?: "user"}@adb_kt" }
        return "$b64 $comment\u0000".toByteArray(Charsets.US_ASCII)
    }

    private fun writeLeU32(
        out: java.io.ByteArrayOutputStream,
        v: Int,
    ) {
        out.write(v and 0xFF)
        out.write((v ushr 8) and 0xFF)
        out.write((v ushr 16) and 0xFF)
        out.write((v ushr 24) and 0xFF)
    }

    private fun computeN0Inv(n: BigInteger): Int {
        val r32 = BigInteger.valueOf(2).pow(32)
        val n0 = n.mod(r32)
        val inv = n0.modInverse(r32)
        return r32
            .subtract(inv)
            .mod(r32)
            .toLong()
            .toInt()
    }

    private fun computeRSquared(
        n: BigInteger,
        words: Int,
    ): BigInteger {
        val bits = 64 * words
        val rSq = BigInteger.ONE.shiftLeft(bits)
        return rSq.mod(n)
    }

    private fun biguintToLeU32Words(
        n: BigInteger,
        words: Int,
    ): IntArray {
        var bytes = n.toByteArray()
        if (bytes.isNotEmpty() && bytes[0].toInt() == 0) {
            bytes = bytes.copyOfRange(1, bytes.size)
        }
        val targetByteLen = words * 4
        val paddedBigEndian = ByteArray(targetByteLen)
        if (bytes.size <= targetByteLen) {
            System.arraycopy(bytes, 0, paddedBigEndian, targetByteLen - bytes.size, bytes.size)
        } else {
            System.arraycopy(bytes, bytes.size - targetByteLen, paddedBigEndian, 0, targetByteLen)
        }
        paddedBigEndian.reverse()

        val out = IntArray(words)
        for (i in 0 until words) {
            val o = i * 4
            out[i] = (paddedBigEndian[o].toInt() and 0xFF) or
                ((paddedBigEndian[o + 1].toInt() and 0xFF) shl 8) or
                ((paddedBigEndian[o + 2].toInt() and 0xFF) shl 16) or
                ((paddedBigEndian[o + 3].toInt() and 0xFF) shl 24)
        }
        return out
    }
}
