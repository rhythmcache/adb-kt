plugins {
    kotlin("jvm")
    alias(libs.plugins.ktlint)
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<Test> {
    testLogging {
        showStandardStreams = true
        events("passed", "skipped", "failed", "standardOut", "standardError")
    }
}

publishing {
    publications {
        create<MavenPublication>("release") {
            from(components["java"])
            groupId = "com.github.rhythmcache"
            artifactId = "adb-kt"
            version = "1.0.0"

            pom {
                name.set("adb-kt")
                description.set("Pure Kotlin, coroutine-native ADB protocol library")
                url.set("https://github.com/rhythmcache/adb-kt")
                licenses {
                    license {
                        name.set("The Apache Software License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("rhythmcache")
                        name.set("rhythmcache")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/rhythmcache/adb-kt.git")
                    developerConnection.set("scm:git:ssh://github.com/rhythmcache/adb-kt.git")
                    url.set("https://github.com/rhythmcache/adb-kt")
                }
            }
        }
    }
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okio)
    implementation(libs.bouncycastle.prov)
    implementation(libs.bouncycastle.pkix)
    implementation(libs.bouncycastle.tls)
    implementation(libs.spake2.kt)
    testImplementation(libs.junit)
}
