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
    runtimeOnly(project(":webp-natives-windows"))
    runtimeOnly(project(":webp-natives-linux"))
    runtimeOnly(project(":webp-natives-macos"))
}

application {
    mainClass.set("Benchmark")
    applicationDefaultJvmArgs = listOf(
        "-Xmx2g",
        "-XX:+UseG1GC",
        "-XX:+AlwaysPreTouch"
    )
}
