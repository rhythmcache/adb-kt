package io.github.rhythmcache.adb

import okio.Buffer
import java.io.InputStream
import java.io.OutputStream

data class AdbFileStat(
    val mode: Int,
    val size: Int,
    val mtime: Int
) {
    val isFile: Boolean get() = (mode and 0x8000) != 0
    val isDirectory: Boolean get() = (mode and 0x4000) != 0
}

class AdbSync internal constructor(private val connection: AdbConnection) {

    suspend fun stat(remotePath: String): AdbFileStat {
        val stream = connection.open("sync:")
        try {
            sendReq(stream, "STAT", remotePath.toByteArray(Charsets.UTF_8))
            val header = readExactly(stream, 16)
            val buf = Buffer().write(header)
            val id = buf.readUtf8(4)
            if (id != "STAT") {
                throw AdbException.Protocol("Invalid STAT response ID: $id")
            }
            val mode = buf.readIntLe()
            val size = buf.readIntLe()
            val mtime = buf.readIntLe()
            sendReq(stream, "QUIT", ByteArray(0))
            return AdbFileStat(mode, size, mtime)
        } finally {
            stream.close()
        }
    }

    suspend fun pull(remotePath: String, output: OutputStream) {
        val stream = connection.open("sync:")
        try {
            sendReq(stream, "RECV", remotePath.toByteArray(Charsets.UTF_8))
            val carry = Buffer()
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
                        val chunk = stream.recv() ?: break
                        carry.write(chunk)
                    }
                    val msg = carry.readUtf8(len.toLong())
                    throw AdbException.RemoteFailure("Sync pull failed: $msg")
                } else if (id == "DATA") {
                    while (carry.size < len) {
                        val chunk = stream.recv() ?: throw AdbException.Protocol("Unexpected EOF in sync DATA block")
                        carry.write(chunk)
                    }
                    val data = carry.readByteArray(len.toLong())
                    output.write(data)
                } else {
                    throw AdbException.Protocol("Unexpected sync pull tag: $id")
                }
            }
            sendReq(stream, "QUIT", ByteArray(0))
        } finally {
            stream.close()
        }
    }

    suspend fun push(input: InputStream, remotePath: String, mode: Int = 33188, mtime: Int = (System.currentTimeMillis() / 1000).toInt()) {
        val stream = connection.open("sync:")
        try {
            val destArg = "$remotePath,$mode".toByteArray(Charsets.UTF_8)
            sendReq(stream, "SEND", destArg)

            val buffer = ByteArray(64 * 1024)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                val chunk = buffer.copyOf(bytesRead)
                sendReq(stream, "DATA", chunk)
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
                val msgBytes = readExactly(stream, len)
                throw AdbException.RemoteFailure("Sync push failed: ${msgBytes.toString(Charsets.UTF_8)}")
            } else if (id != "OKAY") {
                throw AdbException.Protocol("Unexpected sync push response: $id")
            }
            sendReq(stream, "QUIT", ByteArray(0))
        } finally {
            stream.close()
        }
    }

    private suspend fun sendReq(stream: AdbStream, id: String, payload: ByteArray) {
        val buf = Buffer()
        buf.writeUtf8(id)
        buf.writeIntLe(payload.size)
        if (payload.isNotEmpty()) {
            buf.write(payload)
        }
        stream.write(buf.readByteArray())
    }

    private suspend fun readExactly(stream: AdbStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        stream.readFully(buf)
        return buf
    }
}
