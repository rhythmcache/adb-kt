# adb-kt

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF.svg?style=flat&logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API%2024+-3DDC84.svg?style=flat&logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache--2.0-blue.svg)](LICENSE)
[![Coroutines](https://img.shields.io/badge/kotlinx.coroutines-1.8+-blue.svg)](https://github.com/Kotlin/kotlinx.coroutines)

adb-kt is a pure Kotlin, high-performance, coroutine-native implementation of the Android Debug Bridge (ADB) protocol for JVM and Android applications.

It provides full asynchronous, non-blocking ADB host capabilities without relying on native binaries (`adb.exe`), external daemons, or C/C++ libraries.

For full API documentation, see [DOCUMENTATION.md](DOCUMENTATION.md).

---

## Features

- Coroutine-Native Architecture: Built using Kotlin Coroutines (`Flow`, `Channel`, `Semaphore`, and `SupervisorJob`) for structured concurrency and high throughput.
- Android 11+ Wireless Debugging over TLS:
  - Native TLS 1.3 encryption and SPAKE2 password-authenticated wireless pairing (`AdbClient.pairTls` & `AdbClient.connectTls`).
- AOSP-Compliant RSA Authentication:
  - Full support for PKCS#1 and PKCS#8 private keys in PEM and binary DER formats.
  - AOSP 2048-bit RSA mincrypt public key encoding with `n0inv` Montgomery inverse calculation.
  - Supports system `adbkey` files (`~/.android/adbkey`) and in-memory key pairs.
- Shell V2 Protocol Subsystem:
  - Reactive live streaming of `stdout`, `stderr`, and exit status codes using Kotlin `Flow`.
  - Simple execution helper returning `ShellResult`.
- ADB Sync Subsystem:
  - Streamed file `push`, `pull`, and `stat` operations supporting `InputStream`, `OutputStream`, `FileDescriptor`, and Android `Uri`.
- Forward and Reverse Port Mapping:
  - Full support for host-to-device (`forward`) and device-to-host (`reverse`) socket forwarding.
- Type-Safe Endpoint Routing:
  - Supports `Tcp`, `LocalAbstract` (such as `scrcpy`), `LocalFileSystem`, `Jdwp`, and raw service specs.
- Zero Native Dependencies: Pure Kotlin and Okio implementation running on Android (API 24+) and Desktop JVM (JDK 17+).

---

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.rhythmcache:adb-kt:1.0.0")
}
```

---

## Quick Start

### 1. Plain TCP (USB / Port 5555)

Connect to an Android device over Plain TCP and run a shell command:

```kotlin
import io.github.rhythmcache.adb.*
import java.io.File
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    val keyFile = File(System.getProperty("user.home"), ".android/adbkey")
    val keyProvider = FileKeyProvider(keyFile)

    AdbClient.connectTcp("192.168.1.100", 5555, keyProvider).use { client ->
        val result: ShellResult = client.shell("getprop ro.product.model")
        println("Device Model: ${result.stdoutText.trim()}")
    }
}
```

### 2. Wireless Debugging over TLS (Android 11+)

Pair using 6-digit pairing code and connect over TLS 1.3:

```kotlin
// 1. Pair device (once)
AdbClient.pairTls("192.168.1.100", 37625, "851282", keyProvider)

// 2. Connect to Wireless Debugging port
AdbClient.connectTls("192.168.1.100", 41593, keyProvider).use { client ->
    val result = client.shell("getprop ro.product.model")
    println("Device Model: ${result.stdoutText.trim()}")
}
```

For full documentation and examples covering Sync (Push/Pull), Port Forwarding, Custom Transports, and Stream API, see [DOCUMENTATION.md](DOCUMENTATION.md).

---

## Building from Source

```bash
git clone https://github.com/rhythmcache/adb-kt.git
cd adb-kt

./gradlew build
```

---

## License

```text
Copyright 2026 rhythmcache

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
