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
    mainClass.set("SmokeTest")
}
