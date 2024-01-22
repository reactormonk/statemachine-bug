plugins {
    kotlin("jvm") version "1.9.21"
}

group = "org.reactormonk"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    val kstatemachine = "0.24.1"
    implementation("io.github.nsk90:kstatemachine:$kstatemachine")
    implementation("io.github.nsk90:kstatemachine-coroutines:$kstatemachine")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}