plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ktlint)
    `maven-publish`
}

android {
    namespace = "io.github.rhythmcache.adb"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }

    tasks.withType<Test> {
        testLogging {
            showStandardStreams = true
            events("passed", "skipped", "failed", "standardOut", "standardError")
        }
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
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
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okio)
    testImplementation(libs.junit)
}

