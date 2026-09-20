plugins {
    kotlin("multiplatform") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    `maven-publish`
}

group = "bayern.kickner"
version = "0.2.0"
val globalVersion = version.toString()

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        optIn.add("kotlin.concurrent.atomics.ExperimentalAtomicApi")
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm {
        testRuns["test"].executionTask.configure {
            useJUnitPlatform()
        }
    }

    linuxX64()
    macosArm64()
    macosX64()
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies {
            // CoroutineScope is exposed as part of the public API
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            implementation("org.jetbrains.kotlinx:kotlinx-io-core:0.6.0")
            api("io.ktor:ktor-client-core:3.1.1")
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
        }

        jvmMain.dependencies {
        }

        jvmTest.dependencies {
            implementation("io.ktor:ktor-client-cio:3.1.1")
        }
    }
}

publishing {
    repositories {
        maven {
            name = "nexus421Maven"
            url = uri("https://maven.kickner.bayern/releases")
            credentials(PasswordCredentials::class)
            authentication {
                create<BasicAuthentication>("basic")
            }
        }
    }
}