# adb-kt Documentation

Complete API reference and usage guide for the adb-kt Kotlin library.

## Table of Contents

1. [Overview](#1-overview)
2. [Features](#2-features)
3. [Installation](#3-installation)
4. [Connecting to a Device](#4-connecting-to-a-device)
5. [PacketTransport Contract](#5-packettransport-contract)
6. [Key Authentication](#6-key-authentication)
7. [Shell Subsystem](#7-shell-subsystem)
8. [Sync Subsystem](#8-sync-subsystem)
9. [Port Forwarding](#9-port-forwarding)
10. [Reverse Port Mapping](#10-reverse-port-mapping)
11. [Raw Streams and Endpoints](#11-raw-streams-and-endpoints)
12. [Thread Safety and Multiplexing](#12-thread-safety-and-multiplexing)
13. [Exception Handling](#13-exception-handling)

---

## 1. Overview

adb-kt is a pure Kotlin, coroutine-based implementation of the Android Debug Bridge (ADB) protocol. It supports JVM 17+ and Android API 24+ without requiring native binaries or external background daemons.

The core library is transport-agnostic. Only a `PacketTransport` implementation is required. TCP connection support is provided out of the box, while USB, WebSockets, Bluetooth, or custom transports can be implemented externally.

---

## 2. Features

- Pure Kotlin implementation with zero native binary dependencies.
- Coroutine-first API using Kotlin Flow, Channel, Semaphore, and SupervisorJob.
- Transport-agnostic design supporting TCP out of the box, plus custom USB, WebSocket, or Bluetooth transports.
- AOSP-compliant 2048-bit RSA key authentication with PKCS#1, PKCS#8, and binary DER support.
- Multiplexed connection engine running concurrent shell sessions, file syncs, and custom streams over a single connection.
- Reactive Shell v2 protocol subsystem with live Flow streaming.
- Streamed File Sync supporting InputStream, OutputStream, FileDescriptor, and Android Content URIs.
- Type-safe endpoint routing for TCP, LocalAbstract, LocalFileSystem, JDWP, and raw specs.
- Full host port forwarding and device reverse port mapping.

---

## 3. Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.rhythmcache:adb-kt:1.0.0")
}
```

---

## 4. Connecting to a Device

`AdbClient` is the main entry point for interacting with an ADB device. Because `AdbClient` implements `Closeable`, using `.use { }` ensures automatic resource cleanup.

### Connecting over TCP/IP

```kotlin
import io.github.rhythmcache.adb.*
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val keyFile = File(System.getProperty("user.home"), ".android/adbkey")
    val keyProvider = FileKeyProvider(keyFile)

    AdbClient.connect(
        host = "192.168.1.100",
        port = 5555,
        keyProvider = keyProvider,
        handshakeTimeoutMs = 30000
    ).use { client ->
        val result = client.shell("getprop ro.product.model")
        println("Device Model: ${result.stdoutText.trim()}")
    }
}
```

### Connecting over Custom Transports (USB, WebSockets, Bluetooth)

`AdbClient` accepts any implementation of `PacketTransport`:

```kotlin
class UsbPacketTransport(
    private val connection: Any
) : PacketTransport {

    override fun send(pkt: AdbPacket) {
        // Send packet header and payload over USB bulk OUT endpoint
    }

    override fun recv(): AdbPacket {
        // Read 24-byte header and payload from USB bulk IN endpoint
    }

    override fun close() {
        // Release USB interface and close connection
    }
}

// Pass custom transport directly to AdbClient:
val transport = UsbPacketTransport(usbConnection)

AdbClient.connect(transport, keyProvider).use { client ->
    val model = client.shell("getprop ro.product.model")
    println("Model: ${model.stdoutText.trim()}")
}
```

---

## 5. PacketTransport Contract

To implement a custom `PacketTransport` for USB, WebSockets, or Bluetooth, the implementation must meet these requirements:

1. Preserve Packet Ordering: Packets must be delivered in the exact order they were sent.
2. Complete Packet Delivery: `recv()` must return a complete, valid `AdbPacket` (24-byte header plus full payload).
3. Full-Duplex: The transport must support sending and receiving concurrently.
4. Thread Safety: `send()` and `recv()` must handle concurrent calls safely without corrupting packet frames.

---

## 6. Key Authentication

`adb-kt` authentication is managed through the `AdbKeyProvider` interface.

```kotlin
interface AdbKeyProvider {
    suspend fun getKeyPair(): KeyPair
    suspend fun getAdbPublicKeyBytes(): ByteArray? = null
}
```

### FileKeyProvider

`FileKeyProvider` loads RSA private keys from disk. It supports PEM (PKCS#1 and PKCS#8) and binary DER formats. Loaded key pairs are cached in memory to eliminate redundant disk reads. If the key file does not exist, a new 2048-bit RSA key pair is generated and saved.

```kotlin
val keyFile = File(System.getProperty("user.home"), ".android/adbkey")
val keyProvider = FileKeyProvider(keyFile)
```

### MemoryKeyProvider

`MemoryKeyProvider` generates an in-memory ephemeral 2048-bit RSA key pair.

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider = MemoryKeyProvider).use { client ->
    // Connection using ephemeral key
}
```

### Custom Suspendable Key Provider

`AdbKeyProvider` methods are suspendable, allowing integration with Android Keystore, Hardware Security Modules (HSM), or cloud vaults:

```kotlin
class AndroidKeystoreProvider : AdbKeyProvider {
    override suspend fun getKeyPair(): KeyPair {
        // Fetch key pair asynchronously from Android Keystore
    }
}
```

---

## 7. Shell Subsystem

The Shell subsystem uses ADB Shell v2 protocol for command execution and live streaming.

### Running a Command to Completion

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    val result: ShellResult = client.shell("dumpsys battery")

    if (result.isSuccess) {
        println("Output:\n${result.stdoutText}")
    } else {
        println("Error (code ${result.exitCode}):\n${result.stderrText}")
    }
}
```

### ShellResult Properties

- `stdout: ByteArray`: Raw bytes from standard output.
- `stderr: ByteArray`: Raw bytes from standard error.
- `exitCode: Int`: Process exit status code.
- `isSuccess: Boolean`: Returns true if exitCode is 0.
- `stdoutText: String`: UTF-8 decoded standard output string.
- `stderrText: String`: UTF-8 decoded standard error string.

### Live Streaming Shell Output via Flow

```kotlin
import kotlinx.coroutines.flow.collect

AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    client.shellFlow("logcat -v time").collect { chunk ->
        when (chunk) {
            is ShellChunk.Stdout -> print(chunk.text)
            is ShellChunk.Stderr -> System.err.print(chunk.text)
            is ShellChunk.Exit -> println("Process exited with code: ${chunk.code}")
        }
    }
}
```

---

## 8. Sync Subsystem

`client.sync` handles remote file inspection and streaming file transfers.

### Inspecting Remote File Info (`stat`)

```kotlin
val stat: AdbFileStat = client.sync.stat("/sdcard/Download/file.zip")

println("File Size: ${stat.size} bytes")
println("Is File: ${stat.isFile}")
println("Is Directory: ${stat.isDirectory}")
println("Mode: ${stat.mode}")
println("Modified Time: ${stat.mtime}")
```

### Pushing Files to Device (`push`)

`push` accepts any `java.io.InputStream`.

```kotlin
// Pushing from a File:
File("app-release.apk").inputStream().use { input ->
    client.sync.push(input, "/data/local/tmp/app-release.apk")
}

// Pushing from an Android Content URI or FileDescriptor:
val pfd = contentResolver.openFileDescriptor(contentUri, "r")!!
FileInputStream(pfd.fileDescriptor).use { input ->
    client.sync.push(input, "/sdcard/Download/sample.pdf")
}
```

### Pulling Files from Device (`pull`)

`pull` writes to any `java.io.OutputStream`.

```kotlin
// Pulling to a File:
File("downloaded_log.txt").outputStream().use { output ->
    client.sync.pull("/sdcard/log.txt", output)
}

// Pulling to an Android Content URI or FileDescriptor:
val pfd = contentResolver.openFileDescriptor(contentUri, "w")!!
FileOutputStream(pfd.fileDescriptor).use { output ->
    client.sync.pull("/sdcard/DCIM/photo.jpg", output)
}

// Pulling into Memory:
val buffer = java.io.ByteArrayOutputStream()
client.sync.pull("/data/local/tmp/status.json", buffer)
val content = buffer.toString(Charsets.UTF_8)
```

---

## 9. Port Forwarding

`client.forward` manages host-to-device port forwarding rules.

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    // Forward local host TCP port 27183 to device abstract socket 'scrcpy'
    client.forward.add(
        local = AdbEndpoint.Tcp(27183),
        remote = AdbEndpoint.LocalAbstract("scrcpy")
    )

    // Remove specific forward rule
    client.forward.remove(AdbEndpoint.Tcp(27183))

    // Remove all forward rules
    client.forward.removeAll()
}
```

---

## 10. Reverse Port Mapping

`client.reverse` manages device-to-host port forwarding rules.

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    // Forward device TCP port 8080 back to host TCP port 8080
    client.reverse.add(
        local = AdbEndpoint.Tcp(8080),
        remote = AdbEndpoint.Tcp(8080)
    )

    // Remove specific reverse rule
    client.reverse.remove(AdbEndpoint.Tcp(8080))

    // Remove all reverse rules
    client.reverse.removeAll()
}
```

---

## 11. Raw Streams and Endpoints

`client.open()` is the universal escape hatch. It opens direct socket channels to any ADB service, including custom or non-standard daemons.

### Supported Services via `client.open()`

Using `client.open()`, you can interact with services such as:
- scrcpy video and audio streams
- JDWP Java debug sockets
- Android Application Binary (ABB) services
- Custom localabstract, localfilesystem, or dev sockets
- Raw logcat streams

### AdbEndpoint Reference Table

| Endpoint Constructor | Target Service String | Description |
| :--- | :--- | :--- |
| `AdbEndpoint.Tcp(5555)` | `tcp:5555` | TCP socket connection |
| `AdbEndpoint.LocalAbstract("scrcpy")` | `localabstract:scrcpy` | Linux abstract socket |
| `AdbEndpoint.LocalReserved("name")` | `localreserved:name` | Reserved local socket |
| `AdbEndpoint.LocalFilesystem("/tmp/sock")` | `localfilesystem:/tmp/sock` | Unix domain socket file |
| `AdbEndpoint.Dev("/dev/ttyUSB0")` | `dev:/dev/ttyUSB0` | Hardware device node |
| `AdbEndpoint.Jdwp(1234)` | `jdwp:1234` | Java Debug Wire Protocol PID |
| `AdbEndpoint.Raw("shell,v2,raw:logcat")` | `shell,v2,raw:logcat` | Custom raw service string |

### Raw Stream Usage Example

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    client.open(AdbEndpoint.LocalAbstract("scrcpy")).use { stream ->
        stream.writeUtf8("control command\n")

        val chunk: ByteArray? = stream.recv()

        stream.asFlow().collect { bytes ->
            println("Received ${bytes.size} raw bytes from scrcpy stream")
        }
    }
}
```

---

## 12. Thread Safety and Multiplexing

`AdbClient` and `AdbConnection` are thread-safe and safe to use concurrently across multiple coroutines.

### How Multiplexing Works

Only one underlying physical connection (TCP socket or USB interface) is opened per `AdbClient`. Multiple logical streams run concurrently over that single connection:

```text
Single Physical Connection (TCP / USB)
  |
  +-- AdbConnection
       |-- Stream 1: Shell command execution
       |-- Stream 2: File sync push / pull
       |-- Stream 3: scrcpy video stream
       +-- Stream 4: JDWP debugger session
```

You do not need to open multiple `AdbClient` instances to run concurrent commands or transfers on the same device.

---

## 13. Exception Handling

All library exceptions inherit from `AdbException`.

### Exception Types

- `AdbException.Protocol`: Malformed packet headers, magic mismatch, or checksum failure.
- `AdbException.Authentication`: Key parsing failure or missing RSA key pair.
- `AdbException.Transport`: Network socket or physical I/O failure.
- `AdbException.StreamClosed`: Reading or writing to a closed ADB stream.
- `AdbException.Timeout`: Handshake or stream opening timeout.
- `AdbException.RemoteFailure`: Error response returned by remote ADB daemon.
- `AdbException.Io`: Low-level input or output failure.
- `AdbException.ServerFail`: Host daemon command failure.

### Example Error Handling

```kotlin
try {
    AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
        val result = client.shell("ls /root")
    }
} catch (e: AdbException.Authentication) {
    println("Authentication failed: ${e.message}")
} catch (e: AdbException.Timeout) {
    println("Connection timed out: ${e.message}")
} catch (e: AdbException) {
    println("ADB Error: ${e.message}")
}
```
