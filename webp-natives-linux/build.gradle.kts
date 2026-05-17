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
        doFirst {
            environment("JAVA_HOME", gradleJavaHome.get())
        }
        val args = mutableListOf(
            "cmake",
            "-S", nativeLinuxDir.asFile.absolutePath,
            "-B", buildDirForArch.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DWEBP_JNI_SOURCE=${sharedJniSource.absolutePath}"
        )
        args.addAll(cmakeFlags)
        commandLine(args)
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
val copyLinuxSoAarch64 = registerLinuxArchBuild("aarch64")

tasks.register("copyLinuxSos") {
    dependsOn(copyLinuxSoX64, copyLinuxSoAarch64)
}

tasks.register<Delete>("cmakeClean") {
    delete(
        layout.buildDirectory.dir("native/linux-x64"),
        layout.buildDirectory.dir("native/linux-aarch64")
    )
}

tasks.named("processResources") {
    if (isLinux) {
        // The matching arch is the host arch — pick by uname.
        val arch = System.getProperty("os.arch").lowercase()
        if (arch.contains("aarch64") || arch.contains("arm64")) {
            dependsOn(copyLinuxSoAarch64)
        } else {
            dependsOn(copyLinuxSoX64)
        }
    }
}
