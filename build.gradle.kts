plugins {
    kotlin("multiplatform") version "2.2.10"
    `maven-publish`
}

group = "com.github.nexus421"
version = "0.0.1"
val globalVersion = version.toString()

repositories {
    mavenCentral()
}

kotlin {
    jvm()
    js {
        browser()
        nodejs()
    }
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    jvmToolchain(11)

    sourceSets {
        val commonMain by getting
        val commonTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jvmMain by getting
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        val jsMain by getting
        val jsTest by getting {
            dependencies {
                implementation(kotlin("test"))
            }
        }

        // iOS SourceSets: gemeinsamen iosMain/iosTest erzeugen und verknüpfen
        val iosX64Main by getting
        val iosArm64Main by getting
        val iosSimulatorArm64Main by getting
        val iosMain by creating {
            dependsOn(commonMain)
        }
        iosX64Main.dependsOn(iosMain)
        iosArm64Main.dependsOn(iosMain)
        iosSimulatorArm64Main.dependsOn(iosMain)

        val iosX64Test by getting
        val iosArm64Test by getting
        val iosSimulatorArm64Test by getting
        val iosTest by creating {
            dependsOn(commonTest)
        }
        iosX64Test.dependsOn(iosTest)
        iosArm64Test.dependsOn(iosTest)
        iosSimulatorArm64Test.dependsOn(iosTest)
    }
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.github.nexus421"
            artifactId = "Klogger"
            version = globalVersion
            from(components["kotlin"])
        }
    }
}