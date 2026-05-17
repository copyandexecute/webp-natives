plugins {
    `java-library`
}

val isMac = System.getProperty("os.name").lowercase().contains("mac")

dependencies {
    api(project(":webp-natives-core"))
}

val nativeMacDir = layout.projectDirectory.dir("src/native/macos")

/** Shared JNI C source — lives in core so windows/linux can reuse it. */
val sharedJniSource = rootProject.file("webp-natives-core/src/native/webp_jni.c")

/** Adoptium JDK so jni.h is present (Gradle's java.home may point at a JRE). */
val javaToolchains = extensions.getByType<JavaToolchainService>()
val jdkLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}
val gradleJavaHome = jdkLauncher.map { it.metadata.installationPath.asFile.absolutePath }

/**
 * One universal binary (x86_64 + arm64 fat dylib) — CMake's
 * CMAKE_OSX_ARCHITECTURES handles the multi-arch link, no per-arch
 * task needed. Then the single dylib gets copied into both
 * /native/macos/x64/ and /native/macos/arm64/ so the runtime
 * NativeLoader (which looks under <os>/<arch>/) finds the same file
 * regardless of the host architecture.
 */
val buildDirMac = layout.buildDirectory.dir("native/macos")

val cmakeConfigureMacos = tasks.register<Exec>("cmakeConfigureMacos") {
    onlyIf { isMac }
    doFirst {
        environment("JAVA_HOME", gradleJavaHome.get())
    }
    commandLine(
        "cmake",
        "-S", nativeMacDir.asFile.absolutePath,
        "-B", buildDirMac.get().asFile.absolutePath,
        "-DCMAKE_BUILD_TYPE=Release",
        "-DCMAKE_OSX_ARCHITECTURES=x86_64;arm64",
        "-DWEBP_JNI_SOURCE=${sharedJniSource.absolutePath}"
    )
}

val cmakeBuildMacos = tasks.register<Exec>("cmakeBuildMacos") {
    onlyIf { isMac }
    dependsOn(cmakeConfigureMacos)
    doFirst {
        environment("JAVA_HOME", gradleJavaHome.get())
    }
    commandLine(
        "cmake", "--build", buildDirMac.get().asFile.absolutePath,
        "--config", "Release",
        "--parallel"
    )
}

/** Copy the universal dylib into both per-arch resource dirs. */
val copyMacosDylibX64 = tasks.register<Copy>("copyMacosDylibX64") {
    onlyIf { isMac }
    dependsOn(cmakeBuildMacos)
    from(buildDirMac)
    include("libwebp_natives.dylib")
    into(layout.buildDirectory.dir("resources/main/native/macos/x64"))
}

val copyMacosDylibArm64 = tasks.register<Copy>("copyMacosDylibArm64") {
    onlyIf { isMac }
    dependsOn(cmakeBuildMacos)
    from(buildDirMac)
    include("libwebp_natives.dylib")
    into(layout.buildDirectory.dir("resources/main/native/macos/arm64"))
}

tasks.register("copyMacosDylibs") {
    dependsOn(copyMacosDylibX64, copyMacosDylibArm64)
}

tasks.register<Delete>("cmakeClean") {
    delete(layout.buildDirectory.dir("native/macos"))
}

tasks.named("processResources") {
    if (isMac) {
        dependsOn(copyMacosDylibX64, copyMacosDylibArm64)
    }
}
