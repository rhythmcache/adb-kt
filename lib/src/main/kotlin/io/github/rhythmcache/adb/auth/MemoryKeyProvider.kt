package io.github.rhythmcache.adb

import java.security.KeyPair

object MemoryKeyProvider : AdbKeyProvider {
    private val keyPairInstance by lazy { AdbAuth.generateKey() }
    override suspend fun getKeyPair(): KeyPair = keyPairInstance
}
