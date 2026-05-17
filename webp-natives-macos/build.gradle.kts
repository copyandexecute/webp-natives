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

fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/**
 * Register cmake configure + build + copy tasks for one macOS arch.
 *
 * @param arch "x64" or "arm64" — appears in the output resource path
 * @param osxArch the CMake CMAKE_OSX_ARCHITECTURES value ("x86_64" or "arm64")
 */
fun registerMacosArchBuild(arch: String, osxArch: String): TaskProvider<Copy> {
    val suffix = arch.capitalize()
    val buildDirForArch = layout.buildDirectory.dir("native/macos-$arch")

    val configure = tasks.register<Exec>("cmakeConfigureMacos$suffix") {
        onlyIf { isMac }
        doFirst {
            environment("JAVA_HOME", gradleJavaHome.get())
        }
        commandLine(
            "cmake",
            "-S", nativeMacDir.asFile.absolutePath,
            "-B", buildDirForArch.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DCMAKE_OSX_ARCHITECTURES=$osxArch",
            "-DWEBP_JNI_SOURCE=${sharedJniSource.absolutePath}"
        )
    }

    val build = tasks.register<Exec>("cmakeBuildMacos$suffix") {
        onlyIf { isMac }
        dependsOn(configure)
        doFirst {
            environment("JAVA_HOME", gradleJavaHome.get())
        }
        commandLine(
            "cmake", "--build", buildDirForArch.get().asFile.absolutePath,
            "--config", "Release",
            "--parallel"
        )
    }

    return tasks.register<Copy>("copyMacosDylib$suffix") {
        onlyIf { isMac }
        dependsOn(build)
        from(buildDirForArch)
        include("libwebp_natives.dylib")
        into(layout.buildDirectory.dir("resources/main/native/macos/$arch"))
    }
}

val copyMacosDylibX64   = registerMacosArchBuild("x64",   "x86_64")
val copyMacosDylibArm64 = registerMacosArchBuild("arm64", "arm64")

tasks.register("copyMacosDylibs") {
    dependsOn(copyMacosDylibX64, copyMacosDylibArm64)
}

tasks.register<Delete>("cmakeClean") {
    delete(
        layout.buildDirectory.dir("native/macos-x64"),
        layout.buildDirectory.dir("native/macos-arm64")
    )
}

tasks.named("processResources") {
    if (isMac) {
        // Single-arch native: pick the matching dylib for the host.
        val arch = System.getProperty("os.arch").lowercase()
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            dependsOn(copyMacosDylibArm64)
        } else {
            dependsOn(copyMacosDylibX64)
        }
    }
}
