plugins {
    `java-library`
}

val isWindows = System.getProperty("os.name").lowercase().contains("win")

dependencies {
    api(project(":webp-natives-core"))
}

val nativeWindowsDir = layout.projectDirectory.dir("src/native/windows")

/** Shared JNI C source — lives in core so linux/macos can reuse it. */
val sharedJniSource = rootProject.file("webp-natives-core/src/native/webp_jni.c")
// Forward slashes: CMake parses backslashes as escape sequences, and a
// path like D:\a\webp-natives\... (GitHub runner working dir) trips on
// the \a (alert/bell) escape. CMake accepts forward slashes on Windows
// just fine, so we normalise once here.
val jniSrcWin = sharedJniSource.absolutePath.replace("\\", "/")

// JAVA_HOME for CMake — must point at a JDK (not a JRE) so jni.h is reachable.
// Resolve via JavaToolchainService rather than the running gradle daemon's
// `java.home`, since the latter often points at a JRE inside the JDK.
val javaToolchains = extensions.getByType<JavaToolchainService>()
// Adoptium / Temurin specifically — JetBrains Runtime (Android Studio's bundled
// JDK) reports itself as a JDK but ships without C/C++ headers, so CMake's
// `${JAVA_HOME}/include/jni.h` lookup misses. Pinning Adoptium forces Gradle
// to provision a full JDK (auto-downloaded into ~/.gradle/jdks/ if needed).
val jdkLauncher = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
    vendor.set(JvmVendorSpec.ADOPTIUM)
}
val gradleJavaHome = jdkLauncher.map { it.metadata.installationPath.asFile.absolutePath }

// ── WebM (VP9) via vcpkg ──────────────────────────────────────────
// On by default; -Pwebm=false builds WebP-only (no vcpkg needed). If WebM is
// on by default but no vcpkg root is found we fall back to a WebP-only build
// with a warning; an explicit -Pwebm=true with no vcpkg is a hard error.
val webmRequested = project.findProperty("webm") as String?   // null = unspecified (default on)
val webmEnabled = webmRequested?.toBoolean() ?: true
// vcpkg root: explicit env wins, then a checkout at <repo>/vcpkg, then the
// GitHub-runner preinstalled vcpkg.
val vcpkgRoot: String? = System.getenv("VCPKG_ROOT")
    ?: System.getenv("VCPKG_INSTALLATION_ROOT")
    ?: rootProject.file("vcpkg").takeIf { it.isDirectory }?.absolutePath

/** vcpkg/CMake flags for one MSVC triplet (called at execution time). */
fun webmCmakeArgsWin(arch: String): String {
    if (!webmEnabled) return "-DWEBM_ENABLED=OFF"
    val root = vcpkgRoot
    if (root == null) {
        if (webmRequested != null) throw GradleException(
            "WebM requested (-Pwebm=$webmRequested) but no vcpkg root was found. " +
            "Set VCPKG_ROOT, add a vcpkg/ checkout, or drop the flag.")
        logger.warn("[webp-natives] vcpkg not found — building WebP only. " +
            "Set VCPKG_ROOT (or add a vcpkg/ checkout) to enable the VP9/WebM stack.")
        return "-DWEBM_ENABLED=OFF"
    }
    val triplet = if (arch == "arm64") "arm64-windows-static" else "x64-windows-static"
    fun q(p: String) = "\"" + p.replace("\\", "/") + "\""
    return listOf(
        "-DWEBM_ENABLED=ON",
        "-DCMAKE_TOOLCHAIN_FILE=${q("$root/scripts/buildsystems/vcpkg.cmake")}",
        "-DVCPKG_TARGET_TRIPLET=$triplet",
        "-DVCPKG_MANIFEST_DIR=${q(rootProject.projectDir.absolutePath)}",
        "-DVCPKG_OVERLAY_TRIPLETS=${q(rootProject.file("vcpkg-triplets").absolutePath)}"
    ).joinToString(" ")
}

fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Register cmake configure + build + copy DLL tasks for one architecture. */
fun registerWindowsArchBuild(arch: String): TaskProvider<Copy> {
    val suffix = arch.capitalize()
    val cmakeArch = if (arch == "arm64") "ARM64" else "x64"
    val buildDirForArch = layout.buildDirectory.dir("native/windows-$arch")

    val configure = tasks.register<Exec>("cmakeConfigureWindows$suffix") {
        onlyIf { isWindows }
        val projectDirPath = layout.projectDirectory.asFile.absolutePath
        // Build the command in doFirst so the vcpkg-root resolution (and its
        // warning/error) only runs when this task actually executes on Windows,
        // never at configuration time on an unrelated host.
        doFirst {
            val jh = gradleJavaHome.get()
            logger.lifecycle("[webp-natives] JAVA_HOME for cmake: $jh")
            environment("JAVA_HOME", jh)
            commandLine(
                "cmd", "/c",
                "pushd \"$projectDirPath\" && " +
                    "cmake -S src\\native\\windows -B build\\native\\windows-$arch -A $cmakeArch -DWEBP_JNI_SOURCE=\"$jniSrcWin\" ${webmCmakeArgsWin(arch)} && " +
                    "popd"
            )
        }
    }

    val build = tasks.register<Exec>("cmakeBuildWindows$suffix") {
        onlyIf { isWindows }
        dependsOn(configure)
        doFirst {
            val jh = gradleJavaHome.get()
            logger.lifecycle("[webp-natives] JAVA_HOME for cmake: $jh")
            environment("JAVA_HOME", jh)
        }
        val projectDirPath = layout.projectDirectory.asFile.absolutePath
        commandLine(
            "cmd", "/c",
            "pushd \"$projectDirPath\" && " +
                "cmake --build build\\native\\windows-$arch --config Release && " +
                "popd"
        )
    }

    return tasks.register<Copy>("copyWindowsDll$suffix") {
        onlyIf { isWindows }
        dependsOn(build)
        from(buildDirForArch.map { it.dir("Release") })
        from(buildDirForArch.map { it.dir("bin/Release") })
        include("webp_natives.dll")
        into(layout.buildDirectory.dir("resources/main/native/windows/$arch"))
    }
}

val copyWindowsDllX64 = registerWindowsArchBuild("x64")
// arm64 build wired but only fires on an ARM64-capable host or with the
// matching MSVC cross-toolchain. Left out of the default `processResources`
// dependency below so the local x64 dev loop doesn't pull it in.
val copyWindowsDllArm64 = registerWindowsArchBuild("arm64")

tasks.register("copyWindowsDlls") {
    dependsOn(copyWindowsDllX64, copyWindowsDllArm64)
}

tasks.register<Delete>("cmakeClean") {
    delete(
        layout.buildDirectory.dir("native/windows-x64"),
        layout.buildDirectory.dir("native/windows-arm64")
    )
}

tasks.named("processResources") {
    if (isWindows) {
        val arch = System.getProperty("os.arch").lowercase()
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            dependsOn(copyWindowsDllArm64)
        } else {
            dependsOn(copyWindowsDllX64)
        }
    }
}
