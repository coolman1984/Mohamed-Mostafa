import org.jetbrains.kotlin.gradle.dsl.JvmTarget


plugins {
    kotlin("jvm") version "2.1.0"
}

repositories { mavenCentral() }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }

group = "com.mm.smartcgm"
version = "1.0"
