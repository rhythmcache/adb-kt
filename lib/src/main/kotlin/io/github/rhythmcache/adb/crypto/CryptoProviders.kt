package io.github.rhythmcache.adb.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.Security

object CryptoProviders {
    init {
        registerBouncyCastle()
    }

    fun registerBouncyCastle() {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            synchronized(this) {
                if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
            }
        }
    }

    fun rsaKeyFactory(): KeyFactory {
        registerBouncyCastle()
        return KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
    }

    fun getKeyFactory(): KeyFactory = rsaKeyFactory()
}
