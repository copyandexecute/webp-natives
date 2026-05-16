plugins {
    `java-library`
}

dependencies {
    // Aggregator: pulls in the core API + all platform jars so consumers
    // can depend on a single artifact and get correct natives at runtime.
    api(project(":webp-natives-core"))
    api(project(":webp-natives-windows"))
    api(project(":webp-natives-linux"))
    api(project(":webp-natives-macos"))
}
