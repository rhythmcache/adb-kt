package io.github.rhythmcache.adb.rescue

import io.github.rhythmcache.adb.AdbConnection
import io.github.rhythmcache.adb.AdbException
import io.github.rhythmcache.adb.io.RandomAccessSource
import io.github.rhythmcache.adb.sideload.AdbSideload
import io.github.rhythmcache.adb.sideload.SideloadProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

class AdbRescue(private val connection: AdbConnection) {
    /** Convenience overload for plain File */
    fun install(
        file: File,
        blockSize: Int = AdbSideload.DEFAULT_BLOCK_SIZE,
    ): Flow<SideloadProgress> = install(RandomAccessSource.of(file), blockSize)

    /**
     * Install a rescue OTA package over the ADB rescue service using a [RandomAccessSource].
     */
    fun install(
        source: RandomAccessSource,
        blockSize: Int = AdbSideload.DEFAULT_BLOCK_SIZE,
    ): Flow<SideloadProgress> =
        flow {
            val fileSize = source.size
            val service = "rescue-install:$fileSize:$blockSize"

            val stream = connection.open(service)
            source.use {
                try {
                    var bytesTransferred = 0L
                    while (true) {
                        val reqBytes = stream.recv() ?: break
                        val reqStr = String(reqBytes, Charsets.UTF_8).trim()

                        if (reqStr.startsWith("DONEDONE") || reqStr.startsWith("OKAYOKAY")) {
                            emit(SideloadProgress(fileSize, fileSize, 100f))
                            break
                        }
                        if (reqStr.startsWith("FAILFAIL")) {
                            throw AdbException.Protocol("Device reported rescue install failure: $reqStr")
                        }

                        val blockNum =
                            reqStr.toLongOrNull()
                                ?: throw AdbException.Protocol("Invalid block request from device: $reqStr")

                        val offset = blockNum * blockSize
                        if (offset >= fileSize) break

                        val currentChunkSize = minOf(blockSize.toLong(), fileSize - offset).toInt()
                        val buffer = ByteArray(currentChunkSize)
                        source.readFullyAt(offset, buffer, currentChunkSize)

                        stream.write(buffer)

                        bytesTransferred += currentChunkSize
                        val percent = ((offset.toDouble() / fileSize.toDouble()) * 100).toFloat().coerceIn(0f, 100f)
                        emit(SideloadProgress(bytesTransferred, fileSize, percent))
                    }
                } finally {
                    stream.close()
                }
            }
        }.flowOn(Dispatchers.IO)

    /**
     * Get a property from a device in rescue mode.
     */
    suspend fun getProp(prop: String = ""): String {
        val service = if (prop.isBlank()) "rescue-getprop:" else "rescue-getprop:$prop"
        val stream = connection.open(service)
        return try {
            val bytes = stream.readToEnd()
            String(bytes, Charsets.UTF_8).trim()
        } finally {
            stream.close()
        }
    }

    /**
     * Trigger a user data wipe in rescue mode.
     */
    suspend fun wipeUserdata(): String {
        val stream = connection.open("rescue-wipe:userdata:0")
        return try {
            val bytes = stream.readToEnd()
            String(bytes, Charsets.UTF_8).trim()
        } finally {
            stream.close()
        }
    }
}
