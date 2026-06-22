plugins {
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

dependencies {
    implementation(project(":webp-natives-core"))
    // Runtime: bundle every platform jar — only the one matching the host
    // will actually contribute a native, the others contribute empty
    // resource trees. This lets the smoke-test run on any host without
    // having to know which platform module to pick.
    runtimeOnly(project(":webp-natives-windows"))
    runtimeOnly(project(":webp-natives-linux"))
    runtimeOnly(project(":webp-natives-macos"))
}

application {
    mainClass.set("SmokeTest")
}

tasks.register<JavaExec>("runWebm") {
    group = "verification"
    description = "VP9/WebM encode/decode smoke test — writes build/webm-smoke-test*.webm"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("WebmSmokeTest")
    // The 720p scenario holds ~100 ARGB frames (~360 MB) for the array-based
    // encode call; give it headroom independent of the runner's default heap.
    maxHeapSize = "2g"
}
