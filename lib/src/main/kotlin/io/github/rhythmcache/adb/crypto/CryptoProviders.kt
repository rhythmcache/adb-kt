package io.github.rhythmcache.adb.crypto

import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Provider
import java.security.Security

object CryptoProviders {
    val provider: Provider = BouncyCastleProvider()

    init {
        registerBouncyCastle()
    }

    fun registerBouncyCastle() {
        val existing = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
        if (existing !== provider) {
            synchronized(this) {
                val current = Security.getProvider(BouncyCastleProvider.PROVIDER_NAME)
                if (current !== provider) {
                    try {
                        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
                        Security.insertProviderAt(provider, 1)
                    } catch (_: Exception) {
                    }
                }
            }
        }
    }

    fun rsaKeyFactory(): KeyFactory {
        registerBouncyCastle()
        return try {
            KeyFactory.getInstance("RSA", provider)
        } catch (_: Exception) {
            KeyFactory.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        }
    }

    fun rsaKeyPairGenerator(): KeyPairGenerator {
        registerBouncyCastle()
        return try {
            KeyPairGenerator.getInstance("RSA", provider)
        } catch (_: Exception) {
            KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
        }
    }

    fun getKeyFactory(): KeyFactory = rsaKeyFactory()
}
