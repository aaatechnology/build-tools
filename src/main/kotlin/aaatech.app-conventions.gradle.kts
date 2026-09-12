// Precompiled convention plugin (id "aaatech.app-conventions") shared across
// every aaatechnology Android app repo. Apply it AFTER com.android.application in the
// consumer's plugins {} block - it configures the android/androidComponents extensions
// those plugins expose, so it needs them to already exist.
//
// Covers: git-native versionCode/versionName (no version file to keep in sync - see
// AgeCalculator's history for why), common android{} scaffolding, release keystore signing
// from ../key/keystore.properties (populated by CI from secrets), and Jacoco unit-test
// coverage reporting. App-specific things (namespace, applicationId, compileSdk/minSdk/
// targetSdk, per-app resValues, proguard files, viewBinding/compose feature flags) stay in
// each app's own build.gradle.kts.

import com.aaatech.buildtools.AppConfigExtension
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("jacoco")
}

// ---- Git-native versioning ---------------------------------------------------
// versionCode/versionName come from git, not a persisted file. Release builds
// (bundleRelease/assembleRelease) get their exact version from the CI release
// workflow via -PreleaseVersionCode/-PreleaseVersionName - it computes the next
// version from the latest "X.Y.Z" tag (no "v" prefix) plus the release PR's declared
// bump type (major/minor/patch) before this build even starts, and only actually
// creates that tag after a successful Play Store upload. Every other build (local or
// CI debug) derives its own values directly from git instead - nothing is ever
// written back anywhere, so a plain `./gradlew assembleDebug` never touches git state.
fun gitCommitCount(): Int =
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().toIntOrNull() ?: 1

// Debug/local versionName previews the next patch past the latest release tag (e.g.
// tag 2.0.0 -> "2.0.1-dev"), not a raw `git describe` commit-count suffix - a cleaner
// preview of "what patch this would ship as" rather than exposing git internals.
fun gitVersionName(): String {
    val latestTag = providers.exec {
        commandLine("git", "describe", "--tags", "--abbrev=0", "--match", "[0-9]*.[0-9]*.[0-9]*")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()

    if (latestTag.isEmpty()) {
        return "1.0.0-dev"
    }

    val parts = latestTag.split(".")
    val major = parts.getOrElse(0) { "0" }.toIntOrNull() ?: 0
    val minor = parts.getOrElse(1) { "0" }.toIntOrNull() ?: 0
    val nextPatch = (parts.getOrElse(2) { "0" }.toIntOrNull() ?: 0) + 1

    return "$major.$minor.$nextPatch-dev"
}

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        val isRelease = variant.buildType == "release"

        val versionCode = if (isRelease && project.hasProperty("releaseVersionCode")) {
            project.property("releaseVersionCode").toString().toInt()
        } else {
            gitCommitCount()
        }
        val versionName = if (isRelease && project.hasProperty("releaseVersionName")) {
            project.property("releaseVersionName").toString()
        } else {
            gitVersionName()
        }

        variant.outputs.forEach { output ->
            output.versionCode.set(versionCode)
            output.versionName.set(versionName)
        }
    }

    // finalizeDsl runs after the whole project (including a consumer's own
    // android { appConfig { signingKeyVersion = ... } } block, if it sets one) has
    // been evaluated - reading appConfig.signingKeyVersion any earlier would only
    // ever see its default (V2), never a consumer's override.
    finalizeDsl { extension ->
        val appConfig = extension.extensions.getByType<AppConfigExtension>()

        // Reconstructs the ../key/<file> + keystore file that CI's "Reconstruct
        // release keystore" step writes from secrets - identical across every app
        // repo, so the release signingConfig wiring only needs to exist once.
        val keystorePropertiesFile =
            project.rootProject.file("../key/${appConfig.signingKeyVersion.propertiesFileName}")
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = Properties()
            keystoreProperties.load(FileInputStream(keystorePropertiesFile))

            extension.signingConfigs.create("release") {
                storeFile = project.file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }

            extension.buildTypes.getByName("release") {
                signingConfig = extension.signingConfigs.getByName("release")
            }
        }
    }
}

// ---- Common android{} scaffolding --------------------------------------------
extensions.configure<ApplicationExtension> {
    // Lets a consuming app override which key signs release builds, e.g.:
    //   android { appConfig { signingKeyVersion = SigningKeyVersion.V1 } }
    // Defaults to V2 (keystore.properties) - most apps never need to set this at all.
    extensions.create<AppConfigExtension>("appConfig")

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    androidResources {
        localeFilters += "en"
    }
}

// ---- Jacoco unit-test coverage -------------------------------------------------
configure<JacocoPluginExtension> {
    toolVersion = "0.8.13"
}

tasks.withType<Test> {
    configure<JacocoTaskExtension> {
        isIncludeNoLocationClasses = true
        excludes = listOf("jdk.internal.*")
    }
}

val jacocoFileFilter = listOf(
    "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
    "**/*Test*.*", "android/**/*.*", "**/*\$ViewInjector*.*",
    "**/*\$ViewBinder*.*", "**/Lambda$*.class", "**/Lambda.class",
    "**/*Lambda.class", "**/*Lambda*.class", "**/*_LifecycleAdapter.class"
)

fun jacocoClassDirectories() = files(
    // AGP's built-in Kotlin compiler (in effect since removing the separate
    // org.jetbrains.kotlin.android plugin for AGP 9.x compatibility) writes classes
    // here instead of the classic Kotlin Gradle plugin's build/tmp/kotlin-classes/debug.
    fileTree("${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") {
        include("**/*.class")
        exclude(jacocoFileFilter)
    },
    fileTree("${layout.buildDirectory.get()}/intermediates/javac/debug/compileDebugJavaWithJavac/classes") {
        include("**/*.class")
        exclude(jacocoFileFilter)
    }
)

tasks.register<JacocoReport>("jacocoTestReportUnitOnly") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(jacocoClassDirectories())
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("jacoco/testDebugUnitTest.exec")
    })
}
