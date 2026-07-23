package io.github.rhythmcache.adb

object AdbCmd {
    const val SYNC: Int = 0x434e5953
    const val CNXN: Int = 0x4e584e43
    const val AUTH: Int = 0x48545541
    const val OPEN: Int = 0x4e45504f
    const val OKAY: Int = 0x59414b4f
    const val CLSE: Int = 0x45534c43
    const val WRTE: Int = 0x45545257
}

object AdbAuthType {
    const val TOKEN: Int = 1
    const val SIGNATURE: Int = 2
    const val RSAPUBLICKEY: Int = 3
}

const val ADB_VERSION: Int = 0x01000001
const val MAX_PAYLOAD: Int = 1024 * 1024
