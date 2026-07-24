package io.github.rhythmcache.adb

import kotlinx.coroutines.flow.Flow
import okio.Buffer

data class ShellResult(
    val stdout: ByteArray,
    val stderr: ByteArray,
    val exitCode: Int
) {
    val isSuccess: Boolean get() = exitCode == 0
    val stdoutText: String get() = stdout.toString(Charsets.UTF_8)
    val stderrText: String get() = stderr.toString(Charsets.UTF_8)
}

sealed class ShellChunk {
    data class Stdout(val text: String) : ShellChunk()
    data class Stderr(val text: String) : ShellChunk()
    data class Exit(val code: Int) : ShellChunk()
}

private data class ShellHeader(
    val msgId: Int,
    val len: Int
)

object AdbShell {
    private const val MAX_SHELL_FRAME_SIZE = 16 * 1024 * 1024 // 16 MB max frame size safety bound

    fun flow(stream: AdbStream): Flow<ShellChunk> = kotlinx.coroutines.flow.flow {
        val carry = Buffer()

        while (true) {
            val chunk = stream.recv() ?: break
            carry.write(chunk)

            while (carry.size >= 5) {
                val header = carry.peek().use { peeker ->
                    val msgId = peeker.readByte().toInt() and 0xFF
                    val len = peeker.readIntLe()
                    ShellHeader(msgId, len)
                }

                // Corrupted frame length check: throw explicit Protocol Exception to terminate stream
                if (header.len !in 0..MAX_SHELL_FRAME_SIZE) {
                    throw AdbException.Protocol("Invalid shell v2 payload length: ${header.len}")
                }

                // Incomplete frame: break parser loop to allow stream.recv() to fetch more TCP bytes
                if (carry.size < 5L + header.len) break

                carry.skip(5)
                val data = carry.readByteArray(header.len.toLong())

                when (header.msgId) {
                    1 -> emit(ShellChunk.Stdout(data.toString(Charsets.UTF_8)))
                    2 -> emit(ShellChunk.Stderr(data.toString(Charsets.UTF_8)))
                    3 -> {
                        val code = data.firstOrNull()?.toInt()?.and(0xFF) ?: 0
                        emit(ShellChunk.Exit(code))
                    }
                }
            }
        }
    }

    suspend fun collectToResult(stream: AdbStream): ShellResult {
        val stdout = java.io.ByteArrayOutputStream()
        val stderr = java.io.ByteArrayOutputStream()
        var exitCode = 0

        flow(stream).collect { chunk ->
            when (chunk) {
                is ShellChunk.Stdout -> stdout.write(chunk.text.toByteArray(Charsets.UTF_8))
                is ShellChunk.Stderr -> stderr.write(chunk.text.toByteArray(Charsets.UTF_8))
                is ShellChunk.Exit -> exitCode = chunk.code
            }
        }
        return ShellResult(stdout.toByteArray(), stderr.toByteArray(), exitCode)
    }
}

typealias AdbShellStream = AdbShell

suspend fun AdbConnection.runShell(cmd: String): ShellResult {
    val stream = open("shell,v2,raw:$cmd")
    return try {
        AdbShell.collectToResult(stream)
    } finally {
        stream.close()
    }
}
