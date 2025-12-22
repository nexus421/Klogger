plugins {
    kotlin("jvm") version "2.3.0"
    `maven-publish`
}

group = "com.github.nexus421"
version = "0.0.3"
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
    jvmToolchain(17)
}

java {
    withSourcesJar()
    withJavadocJar()
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
    publications {
        create<MavenPublication>("maven") {
            groupId = "bayern.kickner"
            artifactId = "Klogger"
            version = globalVersion
            from(components["java"])
        }
    }
}