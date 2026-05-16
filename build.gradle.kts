import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.plugins.JavaPluginExtension

plugins {
    base
}

allprojects {
    group = rootProject.group
    version = rootProject.version
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
