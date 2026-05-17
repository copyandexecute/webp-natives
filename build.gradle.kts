import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension
import java.util.concurrent.TimeUnit

plugins {
    base
}

// ─────────────────────────────────────────────────────────────────
//  Version
//  Tag push (refs/tags/v1.2.3)          → 1.2.3
//  Branch push in CI (CI env present)   → YY.Q.BUILDCOUNT-branch
//  Local dev (no CI env)                → gradle.properties value
//                                         (so devs get stable coords)
// ─────────────────────────────────────────────────────────────────

fun computeCiAwareVersion(): String {
    val refType = System.getenv("GITHUB_REF_TYPE").orEmpty()
    val refName = System.getenv("GITHUB_REF_NAME").orEmpty()
    if (refType.equals("tag", ignoreCase = true) && refName.isNotBlank()) {
        return refName.removePrefix("v")
    }

    // Local dev: just use whatever's in gradle.properties.
    if (System.getenv("CI") == null && refName.isBlank()) {
        return (findProperty("version") as String?) ?: "0.0.0-LOCAL"
    }

    // CI without tag: YY.Q.BUILDCOUNT-branch
    val now = java.time.LocalDate.now()
    val yy = now.year % 100
    val quarter = (now.monthValue - 1) / 3 + 1
    val quarterStartMonth = ((now.monthValue - 1) / 3) * 3 + 1
    val sinceDate = java.time.LocalDate.of(now.year, quarterStartMonth, 1)
        .minusDays(1)
        .toString()
    val build = runGit("rev-list", "--count", "--since=$sinceDate", "HEAD") ?: "local"

    val rawBranch = refName.takeIf { it.isNotBlank() }
        ?: System.getenv("CI_COMMIT_BRANCH")?.takeIf { it.isNotBlank() }
        ?: runGit("rev-parse", "--abbrev-ref", "HEAD")
        ?: "unknown"
    val branchSlug = rawBranch.replace("/", "-")
    return "$yy.$quarter.$build-$branchSlug"
}

fun runGit(vararg args: String): String? {
    return try {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(rootDir)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val finished = process.waitFor(10, TimeUnit.SECONDS)
        if (finished && process.exitValue() == 0 && output.isNotEmpty()) output else null
    } catch (_: Exception) {
        null
    }
}

allprojects {
    group = "gg.norisk.webp"
    version = computeCiAwareVersion()
}

subprojects {
    repositories {
        mavenCentral()
    }

    plugins.withId("java") {
        apply(plugin = "maven-publish")

        extensions.configure<JavaPluginExtension>("java") {
            toolchain {
                languageVersion.set(JavaLanguageVersion.of(21))
            }
            withSourcesJar()
            withJavadocJar()
        }

        tasks.withType<JavaCompile>().configureEach {
            options.release.set(8)
            options.encoding = "UTF-8"
        }

        tasks.withType<Javadoc>().configureEach {
            (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
        }

        extensions.configure<PublishingExtension>("publishing") {
            publications {
                create<MavenPublication>("maven") {
                    from(components["java"])
                    artifactId = when {
                        project.name == "webp-natives-core"    -> "core"
                        project.name == "webp-natives-windows" -> "windows"
                        project.name == "webp-natives-linux"   -> "linux"
                        project.name == "webp-natives-macos"   -> "macos"
                        project.name == "webp-natives-all"     -> "all"
                        else -> project.name
                    }
                    pom {
                        name.set(project.name)
                        description.set("NoRisk WEBP encode/decode JNI library — libwebp wrapped for the JVM.")
                        url.set("https://github.com/norisk-gg/webp-natives")
                        licenses {
                            license {
                                name.set("Apache License, Version 2.0")
                                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                            }
                        }
                    }
                }
            }
        }
    }
}
