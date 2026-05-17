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

fun String.capitalize(): String = replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/** Register cmake configure + build + copy DLL tasks for one architecture. */
fun registerWindowsArchBuild(arch: String): TaskProvider<Copy> {
    val suffix = arch.capitalize()
    val cmakeArch = if (arch == "arm64") "ARM64" else "x64"
    val buildDirForArch = layout.buildDirectory.dir("native/windows-$arch")

    val configure = tasks.register<Exec>("cmakeConfigureWindows$suffix") {
        onlyIf { isWindows }
        doFirst {
            val jh = gradleJavaHome.get()
            logger.lifecycle("[webp-natives] JAVA_HOME for cmake: $jh")
            environment("JAVA_HOME", jh)
        }
        val projectDirPath = layout.projectDirectory.asFile.absolutePath
        commandLine(
            "cmd", "/c",
            "pushd \"$projectDirPath\" && " +
                "cmake -S src\\native\\windows -B build\\native\\windows-$arch -A $cmakeArch -DWEBP_JNI_SOURCE=\"$jniSrcWin\" && " +
                "popd"
        )
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
        dependsOn(copyWindowsDllX64)
    }
}
