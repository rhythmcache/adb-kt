# adb-kt Documentation

Complete API reference and usage guide for the adb-kt Kotlin library.

## Table of Contents

1. [Overview](#1-overview)
2. [Features](#2-features)
3. [Installation](#3-installation)
4. [Connecting to a Device](#4-connecting-to-a-device)
5. [Device Modes and Banner Detection](#5-device-modes-and-banner-detection)
6. [RandomAccessSource and SAF Integration](#6-randomaccesssource-and-saf-integration)
7. [PacketTransport Contract](#7-packettransport-contract)
8. [Key Authentication](#8-key-authentication)
9. [Shell Subsystem](#9-shell-subsystem)
10. [Streaming Package Installation (APKs and Split APKs)](#10-streaming-package-installation-apks-and-split-apks)
11. [Sideloading Subsystem](#11-sideloading-subsystem)
12. [Rescue Mode Subsystem](#12-rescue-mode-subsystem)
13. [Sync Subsystem](#13-sync-subsystem)
14. [Port Forwarding](#14-port-forwarding)
15. [Reverse Port Mapping](#15-reverse-port-mapping)
16. [Raw Streams and Endpoints](#16-raw-streams-and-endpoints)
17. [Thread Safety and Multiplexing](#17-thread-safety-and-multiplexing)
18. [Exception Handling](#18-exception-handling)

---

## 1. Overview

adb-kt is a pure Kotlin, coroutine-based implementation of the Android Debug Bridge (ADB) protocol. It supports JVM 17+ and Android API 24+ without requiring native binaries or external background daemons.

The core library is transport-agnostic. Only a `PacketTransport` implementation is required. TCP connection support is provided out of the box, while USB, WebSockets, Bluetooth, or custom transports can be implemented externally.

---

## 2. Features

- Pure Kotlin implementation with zero native binary dependencies.
- Coroutine-first API using Kotlin Flow, Channel, Semaphore, and SupervisorJob.
- Transport-agnostic design supporting TCP out of the box, plus custom USB, WebSocket, or Bluetooth transports.
- Automatic device mode detection (`DEVICE`, `RECOVERY`, `SIDELOAD`, `RESCUE`, `HOST`) from initial `CNXN` banner handshakes.
- `RandomAccessSource` API for zero-copy Android SAF (`content://` URI) and `FileDescriptor` stream integration.
- Zero-copy streaming APK installer supporting single APKs and split APK bundles (`.apks` / `.xapk`) via `exec:cmd package`.
- Full OTA sideloading supporting modern demand/pull `sideload-host` and legacy pre-KitKat `sideload:` protocols with progress flows.
- Android Rescue Party subsystem supporting `rescue-install`, `rescue-getprop`, and `rescue-wipe`.
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

## 5. Device Modes and Banner Detection

When connecting to a device, `adb-kt` automatically parses the response payload banner of the `CNXN` handshake packet.

### Inspecting Connection Mode

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    println("Device Mode: ${client.deviceMode}") // DEVICE, RECOVERY, SIDELOAD, RESCUE, HOST, or UNKNOWN
    println("Full Banner: ${client.bannerString}")
}
```

### AdbDeviceMode Enum Values

| Enum Value | Description |
| :--- | :--- |
| `AdbDeviceMode.DEVICE` | Standard running Android OS with active shell and package manager. |
| `AdbDeviceMode.RECOVERY` | Stock or custom recovery environment (TWRP, OrangeFox, etc.). |
| `AdbDeviceMode.SIDELOAD` | ADB Sideload mode (`minadbd`) awaiting OTA package. |
| `AdbDeviceMode.RESCUE` | Emergency Android Rescue Party mode for fixing bootloops. |
| `AdbDeviceMode.HOST` | Host daemon connection. |
| `AdbDeviceMode.UNKNOWN` | Unrecognized or non-standard connection mode. |

---

## 6. RandomAccessSource and SAF Integration

The `RandomAccessSource` interface provides positional, random-access byte reading. This is required by out-of-order block demand protocols like `sideload-host` and `rescue-install`, and allows zero-copy Android SAF (`content://` URI) reading.

### Creating a RandomAccessSource

```kotlin
import io.github.rhythmcache.adb.io.RandomAccessSource

// 1. From a File on disk:
val fileSource = RandomAccessSource.of(File("/path/to/update.zip"))

// 2. From an Android ParcelFileDescriptor (Storage Access Framework):
val pfd = contentResolver.openFileDescriptor(contentUri, "r")!!
val safSource = RandomAccessSource.of(pfd.fileDescriptor, pfd.statSize)

// Wrap with .use {} to guarantee automatic closure:
safSource.use { source ->
    println("Source size: ${source.size} bytes")
}
pfd.close() // Caller maintains ownership of ParcelFileDescriptor
```

---

## 7. PacketTransport Contract

To implement a custom `PacketTransport` for USB, WebSockets, or Bluetooth, the implementation must meet these requirements:

1. Preserve Packet Ordering: Packets must be delivered in the exact order they were sent.
2. Complete Packet Delivery: `recv()` must return a complete, valid `AdbPacket` (24-byte header plus full payload).
3. Full-Duplex: The transport must support sending and receiving concurrently.
4. Thread Safety: `send()` and `recv()` must handle concurrent calls safely without corrupting packet frames.

---

## 8. Key Authentication

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

---

## 9. Shell Subsystem

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

### Live Streaming Shell Output via Flow

```kotlin
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

## 10. Streaming Package Installation (APKs and Split APKs)

`client.install` streams single APKs and split APK bundles (`.apks` / `.xapk`) directly into the Package Manager via `exec:cmd package`, eliminating temporary file copies on `/data/local/tmp/`.

### Installing a Single APK

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    // 1. From a File:
    client.install.install(File("app-release.apk")).collect { progress ->
        println("Install Progress: ${progress.percentage}% - ${progress.statusText}")
    }

    // 2. From an Android SAF Uri via RandomAccessSource:
    val pfd = contentResolver.openFileDescriptor(apkUri, "r")!!
    val source = RandomAccessSource.of(pfd.fileDescriptor, pfd.statSize)
    client.install.install(source, flags = listOf("-r", "-g")).collect { progress ->
        println("Install Progress: ${progress.percentage}%")
    }
    pfd.close()
}
```

### Installing Split APK Bundles (.apks / .xapk)

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    val splits = listOf(
        "base.apk" to RandomAccessSource.of(baseFile),
        "split_config.arm64_v8a.apk" to RandomAccessSource.of(arm64File),
        "split_config.xxhdpi.apk" to RandomAccessSource.of(densityFile)
    )

    client.install.installMultiple(splits, flags = listOf("-r")).collect { progress ->
        println("Bundle Install: ${progress.percentage}% - ${progress.statusText}")
    }
}
```

---

## 11. Sideloading Subsystem

`client.sideload` flashes OTA update packages to devices running in Recovery or Sideload mode.

### Flashing an OTA Zip

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    // Sideloading a File or SAF RandomAccessSource:
    client.sideload.sideload(File("ota_update.zip")).collect { progress ->
        println("Sideloading: ${progress.percentage}% (${progress.bytesTransferred}/${progress.totalBytes} bytes)")
    }
}
```

*Note: `adb-kt` automatically tries modern demand-driven `sideload-host` block protocol first, falling back to legacy pre-KitKat `sideload:` stream protocol if necessary.*

---

## 12. Rescue Mode Subsystem

`client.rescue` interacts with devices in Android Rescue Party mode (`rescue::...`).

### Rescue Operations

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    if (client.deviceMode == AdbDeviceMode.RESCUE) {
        // 1. Fetch rescue properties
        val fingerprint = client.rescue.getProp("ro.build.fingerprint")
        println("Rescue Device Fingerprint: $fingerprint")

        // 2. Install Rescue OTA Package
        client.rescue.install(File("rescue_ota.zip")).collect { progress ->
            println("Rescue Flash: ${progress.percentage}%")
        }

        // 3. Trigger User Data Wipe
        val wipeResult = client.rescue.wipeUserdata()
        println("Wipe Result: $wipeResult")
    }
}
```

---

## 13. Sync Subsystem

`client.sync` handles remote file inspection and streaming file transfers.

### Inspecting Remote File Info (`stat`)

```kotlin
val stat: AdbFileStat = client.sync.stat("/sdcard/Download/file.zip")

println("File Size: ${stat.size} bytes")
println("Is File: ${stat.isFile}")
println("Is Directory: ${stat.isDirectory}")
```

### Pushing Files to Device (`push`)

```kotlin
File("sample.pdf").inputStream().use { input ->
    client.sync.push(input, "/sdcard/Download/sample.pdf")
}
```

### Pulling Files from Device (`pull`)

```kotlin
File("downloaded_log.txt").outputStream().use { output ->
    client.sync.pull("/sdcard/log.txt", output)
}
```

---

## 14. Port Forwarding

`client.forward` manages host-to-device port forwarding rules.

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    // Forward local host TCP port 27183 to device abstract socket 'scrcpy'
    client.forward.add(
        local = AdbEndpoint.Tcp(27183),
        remote = AdbEndpoint.LocalAbstract("scrcpy")
    )

    client.forward.remove(AdbEndpoint.Tcp(27183))
}
```

---

## 15. Reverse Port Mapping

`client.reverse` manages device-to-host port forwarding rules.

```kotlin
AdbClient.connect("192.168.1.100", 5555, keyProvider).use { client ->
    client.reverse.add(
        local = AdbEndpoint.Tcp(8080),
        remote = AdbEndpoint.Tcp(8080)
    )

    client.reverse.remove(AdbEndpoint.Tcp(8080))
}
```

---

## 16. Raw Streams and Endpoints

`client.open()` is the universal escape hatch for raw sockets to any ADB service.

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

---

## 17. Thread Safety and Multiplexing

`AdbClient` and `AdbConnection` are thread-safe and multiplex all concurrent coroutine operations over a single physical connection:

```text
Single Physical Connection (TCP / USB)
  |
  +-- AdbConnection
       |-- Stream 1: Shell command execution
       |-- Stream 2: File sync push / pull
       |-- Stream 3: Streaming APK installer
       +-- Stream 4: Sideload / Rescue stream
```

---

## 18. Exception Handling

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
