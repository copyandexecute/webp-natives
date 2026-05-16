plugins {
    `java-library`
}

val isLinux = System.getProperty("os.name").lowercase().contains("nux")

dependencies {
    api(project(":webp-natives-core"))
}

// TODO: cmake tasks for linux-x64 + linux-aarch64. Will be wired up
// when we set up GitHub Actions matrix builds on ubuntu runners.
// Native source will live in src/native/linux/ and mirror the Windows
// module structure.
