package io.github.rhythmcache.adb

import io.github.rhythmcache.adb.pairing.buildPairingIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

class TcpDropDiagnosticTest {
    private data class StreamFailureRecord(
        val timestampMs: Long,
        val streamName: String,
        val exceptionClass: String,
        val exceptionMessage: String?,
        val threadName: String,
        val deltaMsFromFirst: Long,
        val silentMs: Long,
    )

    @Test
    fun `sustained multiplexed tcp stress test with scrcpy style streams`() =
        runBlocking {
            val host = System.getProperty("adb.test.host") ?: "192.0.0.2"
            val port = (System.getProperty("adb.test.port") ?: "5555").toInt()
            val useTls = (System.getProperty("adb.test.useTls") ?: "false").toBoolean()
            val testDurationSec = (System.getProperty("adb.test.duration.sec") ?: "600").toLong() // Default 10 minutes
            val testDurationMs = testDurationSec * 1000L

            val keyProvider = FileKeyProvider(File(System.getProperty("user.home") + """\.android\adbkey"""))

            println("[${Instant.now()}] [INIT] Connecting to $host:$port (useTls=$useTls, duration=${testDurationSec}s)...")

            val transport: PacketTransport =
                if (useTls) {
                    val identity = buildPairingIdentity(keyProvider)
                    TlsPacketTransport.connect(host, port, identity)
                } else {
                    TcpPacketTransport.connect(host, port)
                }

            val conn = AdbConnection.connect(transport, keyProvider, handshakeTimeoutMs = 15_000)
            println(
                "[${Instant.now()}] [INIT] Connected. mode=${conn.deviceMode}, banner='${conn.bannerString}', maxPayload=${conn.maxPayload}",
            )

            // 1. Setup local host ServerSocket & ADB Reverse rule for authentic scrcpy localabstract forwarding
            var reverseSupported = false
            var serverSocket: ServerSocket? = null
            val reversePort = 27183

            try {
                serverSocket = ServerSocket(reversePort)
                val reverse = AdbReverse(conn)
                reverse.add("localabstract:scrcpy_test", "tcp:$reversePort")
                reverseSupported = true
                println("[${Instant.now()}] [REVERSE] Added reverse rule: localabstract:scrcpy_test -> tcp:$reversePort")
            } catch (e: Exception) {
                println("[${Instant.now()}] [REVERSE] Reverse socket setup notice (${e.message}). Falling back to raw stream services.")
            }

            // Host socket listener helper for feeding test data over reverse sockets
            val serverSocketJobs = mutableListOf<Job>()
            if (reverseSupported && serverSocket != null) {
                serverSocketJobs +=
                    launch(Dispatchers.IO) {
                        val dummyBuffer = ByteArray(65536)
                        while (!serverSocket.isClosed) {
                            try {
                                val clientSocket = serverSocket.accept()
                                launch(Dispatchers.IO) {
                                    try {
                                        val out = clientSocket.getOutputStream()
                                        while (!clientSocket.isClosed) {
                                            out.write(dummyBuffer)
                                            out.flush()
                                        }
                                    } catch (_: Exception) {
                                    } finally {
                                        try {
                                            clientSocket.close()
                                        } catch (_: Exception) {
                                        }
                                    }
                                }
                            } catch (_: Exception) {
                                break
                            }
                        }
                    }
            }

            val isRunning = AtomicBoolean(true)
            val firstException = AtomicReference<Throwable?>(null)
            val firstFailureTimestamp = AtomicLong(0L)

            val failureTimeline = ConcurrentLinkedQueue<StreamFailureRecord>()
            val activeStreamStates = ConcurrentHashMap<String, String>()

            activeStreamStates["Video Stream"] = "ALIVE"
            activeStreamStates["Audio Stream"] = "ALIVE"
            activeStreamStates["Control Stream"] = "ALIVE"
            activeStreamStates["Logcat Stream"] = "ALIVE"

            fun recordFailure(
                streamName: String,
                e: Throwable,
                silentMs: Long,
            ) {
                val now = System.currentTimeMillis()
                firstFailureTimestamp.compareAndSet(0L, now)
                val firstTime = firstFailureTimestamp.get()
                val deltaMs = now - firstTime

                val record =
                    StreamFailureRecord(
                        timestampMs = now,
                        streamName = streamName,
                        exceptionClass = e::class.java.simpleName,
                        exceptionMessage = e.message,
                        threadName = Thread.currentThread().name,
                        deltaMsFromFirst = deltaMs,
                        silentMs = silentMs,
                    )
                failureTimeline.add(record)

                if (firstException.compareAndSet(null, e)) {
                    println("==========================================================================")
                    println("[${Instant.now()}] [FIRST FAILURE DETECTED] Stream '$streamName' failed first!")
                    println("  • Thread Name       : ${Thread.currentThread().name}")
                    println("  • Connection Closed : ${conn.isClosed}")
                    println("  • Silence Duration  : ${silentMs}ms")
                    println("  • Exception Type    : ${e::class.java.name}")
                    println("  • Exception Message : ${e.message}")
                    println("  • STREAM STATES AT MOMENT OF FIRST FAILURE:")
                    activeStreamStates.forEach { (name, state) ->
                        val isCurrent = if (name == streamName) " <-- FAILED FIRST" else ""
                        println("      - $name : $state$isCurrent")
                    }
                    println("  • Stacktrace:\n${e.stackTraceToString()}")
                    println("==========================================================================")
                } else {
                    println(
                        "[${Instant.now()}] [CASCADE FAILURE (+${deltaMs}ms)] Stream '$streamName' failed: ${e::class.java.simpleName}: ${e.message}",
                    )
                }

                activeStreamStates[streamName] = "FAILED (${e::class.java.simpleName}) at +${deltaMs}ms"
                isRunning.set(false)
            }

            // Metrics tracking across multiplexed streams
            val videoBytesRead = AtomicLong(0)
            val audioBytesRead = AtomicLong(0)
            val controlPacketsSent = AtomicLong(0)
            val logcatLinesRead = AtomicLong(0)
            val burstStreamsCompleted = AtomicLong(0)

            // Last activity timestamp per stream for Watchdog diagnostic tracking
            val lastVideoByteAt = AtomicLong(System.currentTimeMillis())
            val lastAudioByteAt = AtomicLong(System.currentTimeMillis())
            val lastControlSentAt = AtomicLong(System.currentTimeMillis())
            val lastLogcatByteAt = AtomicLong(System.currentTimeMillis())

            val startTime = System.currentTimeMillis()
            val jobs = mutableListOf<Job>()

            // Stream 1: Video Stream (Heavy Downstream - localabstract:scrcpy_test or shell:exec cat /dev/zero)
            jobs +=
                launch(Dispatchers.IO) {
                    var lastByteAt = System.currentTimeMillis()
                    try {
                        val serviceSpec = if (reverseSupported) "localabstract:scrcpy_test" else "shell:exec cat /dev/zero"
                        println("[${Instant.now()}] [Video Stream] Opening stream ($serviceSpec)...")
                        val stream = conn.open(serviceSpec)
                        try {
                            while (isRunning.get()) {
                                val chunk = stream.recv() ?: break
                                lastByteAt = System.currentTimeMillis()
                                lastVideoByteAt.set(lastByteAt)
                                videoBytesRead.addAndGet(chunk.size.toLong())
                            }
                        } finally {
                            stream.close()
                        }
                    } catch (e: Throwable) {
                        recordFailure("Video Stream", e, System.currentTimeMillis() - lastByteAt)
                    }
                }

            // Stream 2: Audio Stream (Heavy Downstream - localabstract:scrcpy_test or shell:exec cat /dev/zero)
            jobs +=
                launch(Dispatchers.IO) {
                    var lastByteAt = System.currentTimeMillis()
                    try {
                        val serviceSpec = if (reverseSupported) "localabstract:scrcpy_test" else "shell:exec cat /dev/zero"
                        println("[${Instant.now()}] [Audio Stream] Opening stream ($serviceSpec)...")
                        val stream = conn.open(serviceSpec)
                        try {
                            while (isRunning.get()) {
                                val chunk = stream.recv() ?: break
                                lastByteAt = System.currentTimeMillis()
                                lastAudioByteAt.set(lastByteAt)
                                audioBytesRead.addAndGet(chunk.size.toLong())
                            }
                        } finally {
                            stream.close()
                        }
                    } catch (e: Throwable) {
                        recordFailure("Audio Stream", e, System.currentTimeMillis() - lastByteAt)
                    }
                }

            // Stream 3: Realistic Scrcpy Write-Only Control Stream (Touch/Key events at 10ms intervals)
            jobs +=
                launch(Dispatchers.IO) {
                    val lastWriteAt = System.currentTimeMillis()
                    try {
                        println("[${Instant.now()}] [Control Stream] Opening write-only control stream...")
                        val controlStream = conn.open("shell:exec cat > /dev/null")
                        try {
                            val dummyControlPacket = ByteArray(64) { 0x01 } // 64 byte touch event payload
                            while (isRunning.get()) {
                                controlStream.write(dummyControlPacket)
                                val now = System.currentTimeMillis()
                                lastControlSentAt.set(now)
                                controlPacketsSent.incrementAndGet()
                                delay(10) // 10ms high-frequency control event stream (100 events/sec)
                            }
                        } finally {
                            controlStream.close()
                        }
                    } catch (e: Throwable) {
                        recordFailure("Control Stream", e, System.currentTimeMillis() - lastWriteAt)
                    }
                }

            // Stream 4: Logcat / Status Stream
            jobs +=
                launch(Dispatchers.IO) {
                    var lastByteAt = System.currentTimeMillis()
                    try {
                        println("[${Instant.now()}] [Logcat Stream] Opening logcat stream...")
                        val logcatStream = conn.open("shell:logcat -v time")
                        try {
                            while (isRunning.get()) {
                                val chunk = logcatStream.recv() ?: break
                                lastByteAt = System.currentTimeMillis()
                                lastLogcatByteAt.set(lastByteAt)
                                logcatLinesRead.incrementAndGet()
                            }
                        } finally {
                            logcatStream.close()
                        }
                    } catch (e: Throwable) {
                        recordFailure("Logcat Stream", e, System.currentTimeMillis() - lastByteAt)
                    }
                }

            // Stream 5: Nasty Concurrent OPEN/CLSE Burst Generator (Spawns 15 parallel streams every 500ms)
            jobs +=
                launch(Dispatchers.IO) {
                    while (isRunning.get()) {
                        delay(500)
                        repeat(15) {
                            launch(Dispatchers.IO) {
                                try {
                                    val s = conn.open("shell:id")
                                    s.readToEnd()
                                    s.close()
                                    burstStreamsCompleted.incrementAndGet()
                                } catch (e: Throwable) {
                                    if (isRunning.get()) {
                                        println("[${Instant.now()}] [Burst Stream] Open/Read/Close failed: ${e.message}")
                                    }
                                }
                            }
                        }
                    }
                }

            // 6. 1-Second Watchdog Timer & Diagnostic Logger
            jobs +=
                launch(Dispatchers.IO) {
                    var lastCheckTime = System.currentTimeMillis()
                    var lastVideoBytes = 0L
                    var lastAudioBytes = 0L

                    while (isRunning.get()) {
                        delay(1000)
                        val now = System.currentTimeMillis()
                        val elapsedSec = (now - lastCheckTime) / 1000.0
                        if (elapsedSec <= 0) continue

                        val currentVideoBytes = videoBytesRead.get()
                        val currentAudioBytes = audioBytesRead.get()
                        val videoMBs = ((currentVideoBytes - lastVideoBytes) / 1024.0 / 1024.0) / elapsedSec
                        val audioMBs = ((currentAudioBytes - lastAudioBytes) / 1024.0 / 1024.0) / elapsedSec
                        val totalMBs = videoMBs + audioMBs

                        val totalElapsedSec = (now - startTime) / 1000.0

                        val silentVideoMs = now - lastVideoByteAt.get()
                        val silentAudioMs = now - lastAudioByteAt.get()
                        val silentControlMs = now - lastControlSentAt.get()
                        val silentLogcatMs = now - lastLogcatByteAt.get()

                        println(
                            "[WATCHDOG] [${Instant.now()}] t=${"%.1f".format(totalElapsedSec)}s | " +
                                "videoMBs=${"%.2f".format(
                                    videoMBs,
                                )} (total=${"%.1f".format(currentVideoBytes / 1024.0 / 1024.0)}MB, silent=${silentVideoMs}ms) | " +
                                "audioMBs=${"%.2f".format(
                                    audioMBs,
                                )} (total=${"%.1f".format(currentAudioBytes / 1024.0 / 1024.0)}MB, silent=${silentAudioMs}ms) | " +
                                "controlSent=${controlPacketsSent.get()} (silent=${silentControlMs}ms) | " +
                                "burstsDone=${burstStreamsCompleted.get()} | logcatChunks=${logcatLinesRead.get()} (silent=${silentLogcatMs}ms) | " +
                                "totalBW=${"%.2f".format(totalMBs)} MB/s | connIsClosed=${conn.isClosed}",
                        )

                        lastCheckTime = now
                        lastVideoBytes = currentVideoBytes
                        lastAudioBytes = currentAudioBytes

                        if (now - startTime >= testDurationMs) {
                            println("==========================================================================")
                            println(
                                "[${Instant.now()}] SUCCESS: Sustained multiplexed stress test completed target duration of ${testDurationSec}s!",
                            )
                            println("==========================================================================")
                            isRunning.set(false)
                            break
                        }
                    }
                }

            try {
                // Main loop waiting until completion or failure
                while (isRunning.get() && (System.currentTimeMillis() - startTime < testDurationMs + 5000)) {
                    delay(200)
                }
            } finally {
                isRunning.set(false)

                // Short grace period to collect cascading failures from other stream jobs
                if (failureTimeline.isNotEmpty()) {
                    delay(500)
                }

                println("\n==========================================================================")
                println("[${Instant.now()}] STREAM FAILURE TIMELINE & CASUALTY REPORT")
                println("==========================================================================")
                if (failureTimeline.isEmpty()) {
                    println("  • No failures detected during test run.")
                } else {
                    val list = failureTimeline.toList()
                    val first = list.first()
                    val maxDeltaMs = list.last().deltaMsFromFirst

                    println("  • FIRST STREAM FAILURE : ${first.streamName} (${first.exceptionClass}: ${first.exceptionMessage})")
                    println("  • TOTAL CASCADE DURATION: ${maxDeltaMs}ms across ${list.size} stream failure events\n")

                    println("  Detailed Casualty Event Sequence:")
                    list.forEachIndexed { index, rec ->
                        val tag = if (index == 0) " (FIRST FAILURE)" else ""
                        println(
                            "    [+${rec.deltaMsFromFirst} ms] ${rec.streamName.padEnd(
                                16,
                            )} -> ${rec.exceptionClass}: ${rec.exceptionMessage} [Thread: ${rec.threadName}]$tag",
                        )
                    }

                    println("\n  DIAGNOSTIC ANALYSIS:")
                    if (maxDeltaMs <= 100) {
                        println("    • All streams failed within ${maxDeltaMs}ms of each other.")
                        println("    • CONCLUSION: High confidence TCP/TLS socket transport connection drop.")
                    } else {
                        println("    • Cascading failure took ${maxDeltaMs}ms spread out over time.")
                        println(
                            "    • CONCLUSION: ${first.streamName} failed first while other streams remained operational for ${maxDeltaMs}ms (inspect multiplexing / stream flow control).",
                        )
                    }
                }
                println("==========================================================================\n")

                println("[${Instant.now()}] [CLEANUP] Stopping test jobs, closing server socket & ADB connection...")
                jobs.forEach { it.cancel() }
                serverSocketJobs.forEach { it.cancel() }
                try {
                    serverSocket?.close()
                } catch (_: Exception) {
                }
                conn.close()
                println("[${Instant.now()}] [CLEANUP] ADB Connection closed cleanly. Test finished.")
            }
        }
}
