plugins {
    `java-library`
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")

dependencies {
    api(project(":webp-natives-core"))
}

// TODO: cmake tasks for mac-x64 + mac-arm64 (universal binary via
// CMAKE_OSX_ARCHITECTURES="x86_64;arm64"). Will be wired up when we
// set up GitHub Actions matrix builds on macos runners.
