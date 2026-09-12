// Publishes a precompiled Gradle convention plugin (id "aaatech.app-conventions",
// see src/main/kotlin/) to GitHub Packages, so every aaatechnology Android app repo can apply
// one plugin instead of duplicating git-based versioning, Jacoco setup, and common android{}
// scaffolding in each app's own build.gradle.kts.

plugins {
    `kotlin-dsl`
    `maven-publish`
}

group = "com.aaatech.buildtools"
version = "0.3.0"

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // compileOnly: the consuming app module already applies com.android.application
    // itself (this plugin only configures the extensions it exposes) - this is just
    // needed on the compile classpath to reference its DSL/variant API types.
    compileOnly("com.android.tools.build:gradle:9.3.1")
}

publishing {
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/aaatechnology/build-tools")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
