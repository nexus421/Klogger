plugins {
    kotlin("jvm") version "2.2.10"
    `maven-publish`
}

group = "com.github.nexus421"
version = "0.0.1"
val globalVersion = version.toString()
repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {

            groupId = "com.github.nexus421"
            artifactId = "Klogger"
            version = globalVersion
            from(components["java"])
        }
    }
}