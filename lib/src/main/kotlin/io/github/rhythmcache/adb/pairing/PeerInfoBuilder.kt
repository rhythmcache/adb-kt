package io.github.rhythmcache.adb.pairing

import io.github.rhythmcache.adb.AdbAuth
import java.security.interfaces.RSAPublicKey

/**
 * Builds the PeerInfo we send during pairing: our ADB-formatted RSA public
 * key, matching what real adb sends (adb_wifi.cpp: system_info.type =
 * ADB_RSA_PUB_KEY, data = adb_auth_get_userkey()).
 */
object PeerInfoBuilder {
    fun forOurPublicKey(publicKey: RSAPublicKey): PeerInfo {
        val keyBytes = AdbAuth.encodePublicKeyAdb(publicKey)
        require(keyBytes.size <= PEER_INFO_SIZE - 1) {
            "Encoded public key too large for PeerInfo: ${keyBytes.size} bytes"
        }
        return PeerInfo(PeerInfoType.ADB_RSA_PUB_KEY, keyBytes)
    }

    /** Extracts the device GUID string from a decoded PeerInfo of type ADB_DEVICE_GUID. */
    fun extractDeviceGuid(peerInfo: PeerInfo): String {
        require(peerInfo.type == PeerInfoType.ADB_DEVICE_GUID) {
            "PeerInfo is not a device GUID (type=${peerInfo.type})"
        }
        return PeerInfo.stringFromData(peerInfo.data)
    }

    /** Extracts the raw ADB-formatted public key bytes from a PeerInfo of type ADB_RSA_PUB_KEY. */
    fun extractPublicKeyBytes(peerInfo: PeerInfo): ByteArray {
        require(peerInfo.type == PeerInfoType.ADB_RSA_PUB_KEY) {
            "PeerInfo is not a public key (type=${peerInfo.type})"
        }
        val nul = peerInfo.data.indexOfFirst { it == 0.toByte() }.let { if (it == -1) peerInfo.data.size else it }
        return peerInfo.data.copyOfRange(0, nul)
    }
}
