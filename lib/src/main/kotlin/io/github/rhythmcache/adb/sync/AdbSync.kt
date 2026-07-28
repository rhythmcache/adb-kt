package io.github.rhythmcache.adb

import okio.Buffer
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

data class AdbFileStat(
    val mode: Int,
    val size: Int,
    val mtime: Int,
) {
    val isFile: Boolean get() = (mode and 0x8000) != 0
    val isDirectory: Boolean get() = (mode and 0x4000) != 0
}

class AdbSync internal constructor(private val connection: AdbConnection) {
    suspend fun stat(remotePath: String): AdbFileStat {
        val stream = connection.open("sync:")
        try {
            sendReq(stream, "STAT", remotePath.toByteArray(Charsets.UTF_8))
            val idBytes = readExactly(stream, 4)
            val id = String(idBytes, Charsets.US_ASCII)
            when (id) {
                "STAT" -> {
                    val payload = readExactly(stream, 12)
                    val mode = leInt(payload, 0)
                    val size = leInt(payload, 4)
                    val mtime = leInt(payload, 8)
                    sendReq(stream, "QUIT", ByteArray(0))
                    return AdbFileStat(mode, size, mtime)
                }
                "FAIL" -> {
                    val len = leInt(readExactly(stream, 4))
                    val msg = readExactly(stream, len).toString(Charsets.UTF_8)
                    throw AdbException.RemoteFailure("Sync stat failed: $msg")
                }
                else -> throw AdbException.Protocol("Invalid STAT response ID: $id")
            }
        } finally {
            stream.close()
        }
    }

    suspend fun pull(
        remotePath: String,
        output: OutputStream,
        onProgress: ((bytesDone: Long) -> Unit)? = null,
    ) {
        val stream = connection.open("sync:")
        try {
            sendReq(stream, "RECV", remotePath.toByteArray(Charsets.UTF_8))
            val carry = Buffer()
            var totalDone = 0L
            val tmp = ByteArray(8192)
            while (true) {
                while (carry.size < 8) {
                    val chunk = stream.recv() ?: throw AdbException.Protocol("Unexpected EOF during sync pull")
                    carry.write(chunk)
                }
                val id = carry.readUtf8(4)
                val len = carry.readIntLe()
                if (id == "DONE") {
                    break
                } else if (id == "FAIL") {
                    while (carry.size < len) {
                        val chunk = stream.recv() ?: throw AdbException.Protocol("Unexpected EOF while reading FAIL message")
                        carry.write(chunk)
                    }
                    val msg = carry.readUtf8(len.toLong())
                    throw AdbException.RemoteFailure("Sync pull failed: $msg")
                } else if (id == "DATA") {
                    while (carry.size < len) {
                        val chunk = stream.recv() ?: throw AdbException.Protocol("Unexpected EOF in sync DATA block")
                        carry.write(chunk)
                    }
                    var remaining = len.toLong()
                    while (remaining > 0) {
                        val n = carry.read(tmp, 0, minOf(tmp.size.toLong(), remaining).toInt())
                        if (n == -1) throw AdbException.Protocol("Unexpected EOF copying sync DATA block")
                        output.write(tmp, 0, n)
                        remaining -= n
                        totalDone += n
                        onProgress?.invoke(totalDone)
                    }
                } else {
                    throw AdbException.Protocol("Unexpected sync pull tag: $id")
                }
            }
            sendReq(stream, "QUIT", ByteArray(0))
        } finally {
            stream.close()
        }
    }

    suspend fun pull(
        remotePath: String,
        fd: FileDescriptor,
        onProgress: ((bytesDone: Long) -> Unit)? = null,
    ) {
        FileOutputStream(fd).use { pull(remotePath, it, onProgress) }
    }

    suspend fun push(
        input: InputStream,
        remotePath: String,
        mode: Int = 33188,
        mtime: Int = (System.currentTimeMillis() / 1000).toInt(),
        onProgress: ((bytesDone: Long) -> Unit)? = null,
    ) {
        val stream = connection.open("sync:")
        try {
            val destArg = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            sendReq(stream, "SEND", destArg)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            var totalDone = 0L
            while (input.read(buffer).also { bytesRead = it } != -1) {
                sendData(stream, buffer, bytesRead)
                totalDone += bytesRead
                onProgress?.invoke(totalDone)
            }

            val doneBuf = Buffer()
            doneBuf.writeUtf8("DONE")
            doneBuf.writeIntLe(mtime)
            stream.write(doneBuf.readByteArray())

            val respHeader = readExactly(stream, 8)
            val respBuf = Buffer().write(respHeader)
            val id = respBuf.readUtf8(4)
            val len = respBuf.readIntLe()
            if (id == "FAIL") {
                val msg = readExactly(stream, len).toString(Charsets.UTF_8)
                throw AdbException.RemoteFailure("Sync push failed: $msg")
            } else if (id != "OKAY") {
                throw AdbException.Protocol("Unexpected sync push response: $id")
            }
            sendReq(stream, "QUIT", ByteArray(0))
        } finally {
            stream.close()
        }
    }

    suspend fun push(
        fd: FileDescriptor,
        remotePath: String,
        mode: Int = 33188,
        mtime: Int = (System.currentTimeMillis() / 1000).toInt(),
        onProgress: ((bytesDone: Long) -> Unit)? = null,
    ) {
        FileInputStream(fd).use { push(it, remotePath, mode, mtime, onProgress) }
    }

    private suspend fun sendReq(
        stream: AdbStream,
        id: String,
        payload: ByteArray,
    ) {
        val packet = ByteArray(8 + payload.size)
        packet[0] = id[0].code.toByte()
        packet[1] = id[1].code.toByte()
        packet[2] = id[2].code.toByte()
        packet[3] = id[3].code.toByte()
        val len = payload.size
        packet[4] = (len and 0xFF).toByte()
        packet[5] = ((len shr 8) and 0xFF).toByte()
        packet[6] = ((len shr 16) and 0xFF).toByte()
        packet[7] = ((len shr 24) and 0xFF).toByte()
        if (payload.isNotEmpty()) {
            payload.copyInto(packet, 8)
        }
        stream.write(packet)
    }

    /** Writes a DATA header + [length] bytes from [buffer] without copying [buffer] itself. */
    private suspend fun sendData(
        stream: AdbStream,
        buffer: ByteArray,
        length: Int,
    ) {
        val packet = ByteArray(8 + length)
        packet[0] = 'D'.code.toByte()
        packet[1] = 'A'.code.toByte()
        packet[2] = 'T'.code.toByte()
        packet[3] = 'A'.code.toByte()
        packet[4] = (length and 0xFF).toByte()
        packet[5] = ((length shr 8) and 0xFF).toByte()
        packet[6] = ((length shr 16) and 0xFF).toByte()
        packet[7] = ((length shr 24) and 0xFF).toByte()
        buffer.copyInto(packet, 8, 0, length)
        stream.write(packet)
    }

    private suspend fun readExactly(
        stream: AdbStream,
        count: Int,
    ): ByteArray {
        val buf = ByteArray(count)
        stream.readFully(buf)
        return buf
    }

    private fun leInt(
        b: ByteArray,
        offset: Int = 0,
    ): Int =
        (b[offset].toInt() and 0xFF) or
            ((b[offset + 1].toInt() and 0xFF) shl 8) or
            ((b[offset + 2].toInt() and 0xFF) shl 16) or
            ((b[offset + 3].toInt() and 0xFF) shl 24)
}
