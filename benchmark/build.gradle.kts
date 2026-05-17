plugins {
    application
}

repositories {
    mavenLocal()
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
        vendor.set(JvmVendorSpec.ADOPTIUM)
    }
}

dependencies {
    implementation("gg.norisk.webp:all:2.0.0")
}

application {
    mainClass.set("Benchmark")
    // -Xss for deep recursion in image gen, -Xmx for 4K buffers, GC hints for less noise
    applicationDefaultJvmArgs = listOf(
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-XX:+AlwaysPreTouch"
    )
}
