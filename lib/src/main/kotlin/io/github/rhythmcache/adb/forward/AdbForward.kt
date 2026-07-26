package io.github.rhythmcache.adb

class AdbForward internal constructor(private val connection: AdbConnection) {
    suspend fun add(
        local: String,
        remote: String,
        noRebind: Boolean = false,
    ) {
        val cmd = if (noRebind) "host:forward:norebind:$local;$remote" else "host:forward:$local;$remote"
        val stream = connection.open(cmd)
        val resp =
            try {
                stream.readToEnd().toString(Charsets.UTF_8)
            } finally {
                stream.close()
            }
        if (resp.startsWith("FAIL")) {
            throw AdbException.RemoteFailure("Forward failed: $resp")
        }
    }

    suspend fun add(
        local: AdbEndpoint,
        remote: AdbEndpoint,
        noRebind: Boolean = false,
    ) {
        add(local.toSpec(), remote.toSpec(), noRebind)
    }

    suspend fun remove(local: String) {
        val stream = connection.open("host:killforward:$local")
        stream.close()
    }

    suspend fun remove(local: AdbEndpoint) {
        remove(local.toSpec())
    }

    suspend fun removeAll() {
        val stream = connection.open("host:killforward-all")
        stream.close()
    }
}
