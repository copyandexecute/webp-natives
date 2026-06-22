plugins {
    `java-library`
}

val isLinux = System.getProperty("os.name").lowercase().contains("nux")

dependencies {
    api(project(":webp-natives-core"))
}

val nativeLinuxDir = layout.projectDirectory.dir("src/native/linux")

/** Shared JNI C source — lives in core so windows/macos can reuse it. */
val sharedJniSource = rootProject.file("webp-natives-core/src/native/webp_jni.c")

/** Adoptium JDK so jni.h is present (Gradle's java.home may point at a JRE). */
val javaToolchains = extensions.getByType<JavaToolchainService>()
val jdkLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}
val gradleJavaHome = jdkLauncher.map { it.metadata.installationPath.asFile.absolutePath }

// ── WebM (VP9) via vcpkg ──────────────────────────────────────────
// On by default; -Pwebm=false builds WebP-only (no vcpkg needed). Default-on
// with no vcpkg falls back to WebP-only (warning); explicit -Pwebm=true errors.
val webmRequested = project.findProperty("webm") as String?   // null = unspecified (default on)
val webmEnabled = webmRequested?.toBoolean() ?: true
val vcpkgRoot: String? = System.getenv("VCPKG_ROOT")
    ?: System.getenv("VCPKG_INSTALLATION_ROOT")
    ?: rootProject.file("vcpkg").takeIf { it.isDirectory }?.absolutePath

/** vcpkg/CMake flags for one triplet (PIC linux triplets live in vcpkg-triplets/). Called at execution time. */
fun webmCmakeArgs(triplet: String): List<String> {
    if (!webmEnabled) return listOf("-DWEBM_ENABLED=OFF")
    val root = vcpkgRoot
    if (root == null) {
        if (webmRequested != null) throw GradleException(
            "WebM requested (-Pwebm=$webmRequested) but no vcpkg root was found. " +
            "Set VCPKG_ROOT, add a vcpkg/ checkout, or drop the flag.")
        logger.warn("[webp-natives] vcpkg not found — building WebP only. " +
            "Set VCPKG_ROOT (or add a vcpkg/ checkout) to enable the VP9/WebM stack.")
        return listOf("-DWEBM_ENABLED=OFF")
    }
    return listOf(
        "-DWEBM_ENABLED=ON",
        "-DCMAKE_TOOLCHAIN_FILE=$root/scripts/buildsystems/vcpkg.cmake",
        "-DVCPKG_TARGET_TRIPLET=$triplet",
        "-DVCPKG_MANIFEST_DIR=${rootProject.projectDir.absolutePath}",
        "-DVCPKG_OVERLAY_TRIPLETS=${rootProject.file("vcpkg-triplets").absolutePath}"
    )
}

/**
 * Register cmake configure + build + copy-.so tasks for one architecture.
 *
 * @param arch  "x64" or "aarch64" — appears in the output resource path
 * @param cmakeFlags additional -D flags (e.g. cross-toolchain selection)
 */
fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

fun registerLinuxArchBuild(arch: String, cmakeFlags: List<String> = emptyList()): TaskProvider<Copy> {
    val suffix = arch.capitalize()
    val buildDirForArch = layout.buildDirectory.dir("native/linux-$arch")

    val configure = tasks.register<Exec>("cmakeConfigureLinux$suffix") {
        onlyIf { isLinux }
        // Build the command in doFirst so vcpkg-root resolution only runs when
        // this task executes on Linux, never at configuration time elsewhere.
        doFirst {
            environment("JAVA_HOME", gradleJavaHome.get())
            val args = mutableListOf(
                "cmake",
                "-S", nativeLinuxDir.asFile.absolutePath,
                "-B", buildDirForArch.get().asFile.absolutePath,
                "-DCMAKE_BUILD_TYPE=Release",
                "-DWEBP_JNI_SOURCE=${sharedJniSource.absolutePath}"
            )
            args.addAll(webmCmakeArgs("$arch-linux"))
            args.addAll(cmakeFlags)
            commandLine(args)
        }
    }

    val build = tasks.register<Exec>("cmakeBuildLinux$suffix") {
        onlyIf { isLinux }
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

    return tasks.register<Copy>("copyLinuxSo$suffix") {
        onlyIf { isLinux }
        dependsOn(build)
        from(buildDirForArch)
        include("libwebp_natives.so")
        into(layout.buildDirectory.dir("resources/main/native/linux/$arch"))
    }
}

val copyLinuxSoX64 = registerLinuxArchBuild("x64")
// aarch64 build is wired but only runs on an aarch64 host (or with a
// cross-toolchain in place — CI runs it on an ubuntu-24.04-arm runner).
// Use "arm64" (not "aarch64") for the resource-dir name so the path
// matches NativeLoader.detectArch(), which maps both os.arch values to
// "arm64" for cross-platform consistency with macos/windows.
val copyLinuxSoArm64 = registerLinuxArchBuild("arm64")

tasks.register("copyLinuxSos") {
    dependsOn(copyLinuxSoX64, copyLinuxSoArm64)
}

tasks.register<Delete>("cmakeClean") {
    delete(
        layout.buildDirectory.dir("native/linux-x64"),
        layout.buildDirectory.dir("native/linux-arm64")
    )
}

tasks.named("processResources") {
    if (isLinux) {
        // The matching arch is the host arch — pick by uname.
        val arch = System.getProperty("os.arch").lowercase()
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            dependsOn(copyLinuxSoArm64)
        } else {
            dependsOn(copyLinuxSoX64)
        }
    }
}
