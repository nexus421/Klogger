plugins {
    kotlin("jvm") version "2.4.0"
    kotlin("plugin.serialization") version "2.4.0"
    `maven-publish`
}

group = "com.github.nexus421"
version = "0.3.0"
val globalVersion = version.toString()
repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // api, not implementation: CoroutineScope is part of the public API (logToLoki, HttpLogAppender, LokiAppender.scope)
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // compileOnly: the SLF4J bridge only activates when the app already has SLF4J (e.g. via Ktor).
    // Klogger itself never pulls SLF4J in.
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    testImplementation("org.slf4j:slf4j-api:2.0.17")
}

tasks.test {
    useJUnitPlatform()
    filter { excludeTestsMatching("*WithoutSlf4jTest") }
}

// Proves that Klogger, including slf4jBridge {}, works for apps that have no SLF4J on the classpath.
val testWithoutSlf4j by tasks.registering(Test::class) {
    description = "Runs *WithoutSlf4jTest with slf4j-api removed from the runtime classpath."
    group = "verification"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath.filter { !it.name.startsWith("slf4j-api") }
    filter { includeTestsMatching("*WithoutSlf4jTest") }
}

tasks.check {
    dependsOn(testWithoutSlf4j)
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