package io.github.rhythmcache.adb

import java.security.KeyPair

interface AdbKeyProvider {
    suspend fun getKeyPair(): KeyPair
    suspend fun getAdbPublicKeyBytes(): ByteArray? = null
}
