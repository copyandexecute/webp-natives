pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Resolves JDK toolchains via foojay/Disco API — required so Gradle
    // can auto-provision Adoptium Temurin if a matching JDK isn't already
    // present locally. Same plugin clientside uses.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "webp-natives"

include("webp-natives-core")
include("webp-natives-windows")
include("webp-natives-linux")
include("webp-natives-macos")
include("webp-natives-all")
include("smoke-test")
