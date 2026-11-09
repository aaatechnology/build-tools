// Precompiled convention plugin (id "com.aaatech.build-tools") shared across
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
import com.aaatech.buildtools.appName
import com.aaatech.buildtools.enableLog
import com.aaatech.buildtools.printLog
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import groovy.json.JsonSlurper
import org.gradle.testing.jacoco.plugins.JacocoPluginExtension
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.gradle.testing.jacoco.tasks.JacocoReport
import java.io.File
import java.io.FileInputStream
import java.util.Properties
import java.util.concurrent.Callable

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

// ---- Variants, for printAppInfo ------------------------------------------------
// One entry per variant, filled in by onVariants below and read by printAppInfo at
// execution time. The applicationId stays a Provider so suffixes and per-flavour
// overrides resolve exactly as AGP builds them.
class VariantInfo(
    val flavor: String,
    val buildType: String,
    val variantName: String,
    val applicationId: Provider<String>,
)

val variantInfos = mutableListOf<VariantInfo>()

extensions.configure<ApplicationAndroidComponentsExtension> {
    onVariants { variant ->
        variantInfos += VariantInfo(
            flavor = variant.flavorName?.takeIf { it.isNotEmpty() } ?: NO_FLAVOR,
            buildType = variant.buildType.orEmpty(),
            variantName = variant.name,
            applicationId = variant.applicationId,
        )

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

        // Lets a consuming app write, per build type:
        //   debug { appName = "..."; enableLog = true; printLog = true }
        // instead of repeating buildConfigField(...) calls itself - generates the
        // exact same BuildConfig.APP_NAME/ENABLE_LOG/PRINT_LOG fields.
        extension.buildTypes.forEach { buildType ->
            buildType.buildConfigField("String", "APP_NAME", "\"${buildType.appName}\"")
            buildType.buildConfigField("boolean", "ENABLE_LOG", buildType.enableLog.toString())
            buildType.buildConfigField("boolean", "PRINT_LOG", buildType.printLog.toString())
        }

        // Opt-in only - see AppConfigExtension.supportedLocales. Read here (not in
        // the eager androidResources block below) for the same reason
        // signingKeyVersion is: a consumer's own
        // android { appConfig { supportedLocales = ... } } must be evaluated first.
        if (appConfig.supportedLocales.isNotEmpty()) {
            extension.androidResources.localeFilters += appConfig.supportedLocales
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

// ---- Coverage variant ---------------------------------------------------------
// The build variant whose unit tests the coverage tasks run and read: "debug" by
// default, or e.g. "freeDebug" for an app with product flavors, which has no
// testDebugUnitTest task and keeps its classes/.exec data under the flavored
// variant name. Pass -PcoverageVariant=<variant> (ci-build-test.yml does, for each
// flavour it builds, from printAppInfo's <FLAVOR>_DEBUG_VARIANT).
val coverageVariant: String =
    (findProperty("coverageVariant") as String?)?.takeIf { it.isNotBlank() } ?: "debug"
val coverageVariantTask = coverageVariant.replaceFirstChar { it.uppercase() }
val coverageTestTask = "test${coverageVariantTask}UnitTest"

fun coverageKotlinClasses(buildDir: Any) =
    "$buildDir/intermediates/built_in_kotlinc/$coverageVariant/compile${coverageVariantTask}Kotlin/classes"

fun coverageJavaClasses(buildDir: Any) =
    "$buildDir/intermediates/javac/$coverageVariant/compile${coverageVariantTask}JavaWithJavac/classes"

fun jacocoClassDirectories() = files(
    // AGP's built-in Kotlin compiler (in effect since removing the separate
    // org.jetbrains.kotlin.android plugin for AGP 9.x compatibility) writes classes
    // here instead of the classic Kotlin Gradle plugin's build/tmp/kotlin-classes/debug.
    fileTree(coverageKotlinClasses(layout.buildDirectory.get())) {
        include("**/*.class")
        exclude(jacocoFileFilter)
    },
    fileTree(coverageJavaClasses(layout.buildDirectory.get())) {
        include("**/*.class")
        exclude(jacocoFileFilter)
    }
)

tasks.register<JacocoReport>("jacocoTestReportUnitOnly") {
    dependsOn(coverageTestTask)
    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    sourceDirectories.setFrom(files("${project.projectDir}/src/main/java"))
    classDirectories.setFrom(jacocoClassDirectories())
    // AGP's own instrumentation (android.buildTypes.debug { enableUnitTestCoverage =
    // true }, which every consumer of this plugin sets) writes .exec data under
    // outputs/unit_test_code_coverage/, not the classic Gradle jacoco plugin's
    // build/jacoco/<taskName>.exec default - that path is never actually produced
    // once AGP is managing instrumentation, so this task previously always read an
    // empty/nonexistent file and silently reported "report not found" downstream
    // (surfaced via ci-build-test.yml's coverage PR comment). Both patterns are kept
    // so a project not using enableUnitTestCoverage still finds the classic path.
    executionData.setFrom(fileTree(layout.buildDirectory.get()) {
        include("jacoco/$coverageTestTask.exec")
        include("outputs/unit_test_code_coverage/${coverageVariant}UnitTest/*.exec")
    })
}

// ---- Multi-module coverage (every subproject in the build) ---------------------
// Both tasks below are registered unconditionally, work with zero config - harmless
// either way, since they only ever run when explicitly requested. A module that
// isn't Android/Kotlin, has no tests, or lacks android.buildTypes.debug {
// enableUnitTestCoverage = true } (the same flag this plugin's own
// jacocoTestReportUnitOnly relies on) simply contributes nothing: dependsOn on a
// `tasks.matching {}` empty result and fileTree over a directory that doesn't
// exist are both already no-ops, not errors - so there's no per-module config to
// add here, and no per-repo build-tools change needed for a new project shaped
// like this one (AGP's built-in Kotlin compiler, standard debug/release build
// types).

fun moduleSourceDirs(module: Project) =
    listOf(File(module.projectDir, "src/main/java"), File(module.projectDir, "src/main/kotlin"))

// Kotlin and Java classes, so a module still partly in Java is measured in full.
fun moduleClassDirs(module: Project) = listOf(
    coverageKotlinClasses(module.layout.buildDirectory.get()),
    coverageJavaClasses(module.layout.buildDirectory.get()),
).map { dir ->
    fileTree(dir) {
        include("**/*.class")
        exclude(jacocoFileFilter)
    }
}

// AGP's instrumentation (enableUnitTestCoverage) or, without it, the classic
// jacoco plugin's per-task file - whichever the module produces.
fun moduleExecData(module: Project) = fileTree(module.layout.buildDirectory.get()) {
    include("outputs/unit_test_code_coverage/${coverageVariant}UnitTest/*.exec")
    include("jacoco/$coverageTestTask.exec")
}

// One report per subproject (registered ON that subproject, not on this app
// module, so `./gradlew moduleJacocoReport` from the root runs it in every
// project that has it, each writing to its own build/reports/jacoco/
// moduleJacocoReport/moduleJacocoReport.xml) - lets CI show a per-module coverage
// breakdown without hardcoding module names anywhere, alongside the combined
// (all-modules) figure below.
project.rootProject.subprojects.forEach { module ->
    module.tasks.register<JacocoReport>("moduleJacocoReport") {
        group = "verification"
        description = "Generates this module's own JaCoCo coverage report."
        dependsOn(module.tasks.matching { it.name == coverageTestTask })

        reports {
            xml.required.set(true)
            html.required.set(true)
        }

        sourceDirectories.setFrom(files(moduleSourceDirs(module)))
        classDirectories.setFrom(files(moduleClassDirs(module)))
        executionData.setFrom(files(moduleExecData(module)))
    }
}

tasks.register<JacocoReport>("combinedJacocoReport") {
    group = "verification"
    description = "Generates one merged JaCoCo coverage report across every subproject."

    val modules = project.rootProject.subprojects

    modules.forEach { module ->
        dependsOn(module.tasks.matching { it.name == coverageTestTask })
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    // Every subproject's own build dir, not just this app module's - unlike
    // jacocoClassDirectories()/sourceDirectories above, which are scoped to project.
    sourceDirectories.setFrom(files(modules.flatMap { moduleSourceDirs(it) }))
    classDirectories.setFrom(files(modules.flatMap { moduleClassDirs(it) }))
    executionData.setFrom(files(modules.map { moduleExecData(it) }))
}

// ---- UI (instrumented) test coverage --------------------------------------------
// connected<Variant>AndroidTest, with android.buildTypes.debug {
// enableAndroidTestCoverage = true }, writes .ec files under
// outputs/code_coverage/<variant>AndroidTest/connected/<device>/. This report is kept
// apart from the unit-test ones (CI shows the two figures separately) and doesn't
// depend on the connected task, which needs a device: CI runs both in one Gradle call
// and mustRunAfter keeps the order. A module without UI tests or without the flag
// contributes nothing, as with the unit-test reports.
val coverageUiTestTask = "connected${coverageVariantTask}AndroidTest"

fun moduleUiTestExecData(module: Project) = fileTree(module.layout.buildDirectory.get()) {
    include("outputs/code_coverage/${coverageVariant}AndroidTest/connected/**/*.ec")
}

// The classes the UI tests ran: AGP's ASM-transform output, where Hilt has rewritten its
// @HiltAndroidApp/@AndroidEntryPoint classes (the compilers' copies of those don't match the
// .ec data, so JaCoCo would count them as 0%), less the classes hiltJavaCompile adds there
// (Dagger components etc.), so the same classes are measured as from the compilers. Read when
// the task runs, as the folder may not exist yet at configuration; a module without an ASM
// transform falls back to the compilers' output.
fun moduleUiTestClassDirs(module: Project) = files(Callable {
    val variantClasses = module.layout.buildDirectory.get().asFile.resolve("intermediates/classes/$coverageVariant")
    val transformed = variantClasses.resolve("transform${coverageVariantTask}ClassesWithAsm/dirs")
    if (!transformed.isDirectory) return@Callable moduleClassDirs(module)
    val hiltGenerated = variantClasses.resolve("hiltJavaCompile$coverageVariantTask")
    fileTree(transformed) {
        include("**/*.class")
        exclude(jacocoFileFilter)
        exclude { !it.isDirectory && hiltGenerated.resolve(it.relativePath.pathString).isFile }
    }
})

tasks.register<JacocoReport>("uiTestJacocoReport") {
    group = "verification"
    description = "Generates one merged JaCoCo report of the instrumented (UI) tests across every subproject."

    val modules = project.rootProject.subprojects

    modules.forEach { module ->
        mustRunAfter(module.tasks.matching { it.name == coverageUiTestTask })
    }

    reports {
        xml.required.set(true)
        html.required.set(true)
    }

    sourceDirectories.setFrom(files(modules.flatMap { moduleSourceDirs(it) }))
    classDirectories.setFrom(files(modules.map { moduleUiTestClassDirs(it) }))
    executionData.setFrom(files(modules.map { moduleUiTestExecData(it) }))
}

// Writes every subproject's name, one per line, to a file CI can just `cat` -
// the actual live Gradle project model, not a text-parsed guess at
// settings.gradle.kts's include(...) syntax (which varies: one include() per
// module vs one call listing several, Kotlin DSL vs Groovy, dynamic inclusion,
// etc.). This is what tells CI's coverage step how many modules exist, so it
// knows whether to print a per-module breakdown at all.
tasks.register("listCoverageModules") {
    group = "verification"
    description = "Writes every subproject name (one per line) for CI's coverage breakdown to read."

    val outputFile = layout.buildDirectory.file("coverage-modules.txt")
    // Captured at configuration time, not read from `project`/`rootProject`
    // inside doLast - the latter is unsupported under the configuration cache
    // (Task.project can't be invoked at execution time).
    val moduleNames = project.rootProject.subprojects.map { it.name }
    outputs.file(outputFile)

    doLast {
        outputFile.get().asFile.writeText(moduleNames.joinToString("\n"))
    }
}

// ---- App info for CI/CD (printAppInfo) ----------------------------------------------
// The CI/CD workflows ask Gradle, not their own inputs, what the app is made of: each
// selected flavour's applicationId, Firebase app ID, task names and output paths, as
// KEY=value lines a workflow appends to $GITHUB_ENV:
//
//   ./gradlew -q printAppInfo -PappFlavors=all
//
//   APP_FLAVORS=free pro
//   FREE_APPLICATION_ID=com.example.app
//   FREE_FIREBASE_APP_ID=1:123:android:abc      (from google-services.json, when it has the package)
//   FREE_DEBUG_ASSEMBLE_TASK=assembleFreeDebug
//   FREE_DEBUG_TEST_TASK=testFreeDebugUnitTest
//   FREE_DEBUG_UI_TEST_TASK=connectedFreeDebugAndroidTest
//   FREE_DEBUG_VARIANT=freeDebug                 (coverageVariant)
//   FREE_DEBUG_APK_DIR=app/build/outputs/apk/free/debug
//   FREE_RELEASE_BUNDLE_TASK=bundleFreeRelease
//   FREE_RELEASE_ASSEMBLE_TASK=assembleFreeRelease
//   FREE_RELEASE_BUNDLE_DIR=app/build/outputs/bundle/freeRelease
//   FREE_RELEASE_APK_DIR=app/build/outputs/apk/free/release
//   FREE_RELEASE_MAPPING=app/build/outputs/mapping/freeRelease/mapping.txt
//
// An app without product flavours has exactly one flavour, named "default" (never an
// empty string): DEFAULT_APPLICATION_ID, DEFAULT_DEBUG_ASSEMBLE_TASK=assembleDebug, ...
//
// -PappFlavors is "all" (every flavour, the default) or a comma/space-separated list.
// The task fails, before anything is built, when a requested flavour doesn't exist, a
// real flavour is named "default", or google-services.json is present but has no client
// for a selected flavour's applicationId (its Firebase upload would go to the wrong app).

/** The flavour name standing for "no product flavours". */
val NO_FLAVOR = "default"

/** FREE for "free", FREE_PROD for "freeProd": the prefix of a flavour's variables. */
fun flavorKey(flavor: String): String =
    flavor.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace(Regex("[^A-Za-z0-9]"), "_").uppercase()

tasks.register("printAppInfo") {
    group = "help"
    description = "Prints each selected flavour's applicationId, Firebase app ID, tasks and outputs for CI/CD."

    val requested = (findProperty("appFlavors") as String?)?.takeIf { it.isNotBlank() } ?: "all"
    val moduleDir = project.rootDir.toPath().relativize(project.projectDir.toPath()).toString().ifEmpty { "." }
    val projectDirFile = project.projectDir
    val infos = variantInfos

    doLast {
        val flavors = infos.map { it.flavor }.distinct()
        if (infos.any { it.flavor == NO_FLAVOR } && flavors.size > 1) {
            throw GradleException("A product flavour is named \"$NO_FLAVOR\", which stands for \"no flavours\" in CI/CD. Rename it.")
        }

        val selected = if (requested.trim() == "all") {
            flavors
        } else {
            requested.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        }
        val unknown = selected - flavors.toSet()
        if (unknown.isNotEmpty()) {
            throw GradleException("Unknown flavour(s) ${unknown.joinToString()}: this app has ${flavors.joinToString()}.")
        }
        if (selected.isEmpty()) throw GradleException("No flavours selected (-PappFlavors=$requested).")

        // Firebase clients: a flavour's own src/<flavor>/google-services.json, else the module's.
        fun firebaseClients(flavor: String): Map<String, String>? {
            val file = listOf(File(projectDirFile, "src/$flavor/google-services.json"), File(projectDirFile, "google-services.json"))
                .firstOrNull { it.isFile } ?: return null
            @Suppress("UNCHECKED_CAST")
            val json = JsonSlurper().parse(file) as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val clients = json["client"] as? List<Map<String, Any?>> ?: return emptyMap()
            return clients.mapNotNull { client ->
                @Suppress("UNCHECKED_CAST")
                val info = client["client_info"] as? Map<String, Any?> ?: return@mapNotNull null
                @Suppress("UNCHECKED_CAST")
                val pkg = (info["android_client_info"] as? Map<String, Any?>)?.get("package_name") as? String
                val appId = info["mobilesdk_app_id"] as? String
                if (pkg != null && appId != null) pkg to appId else null
            }.toMap()
        }

        val lines = mutableListOf("APP_FLAVORS=${selected.joinToString(" ")}")
        for (flavor in selected) {
            val key = flavorKey(flavor)
            val debug = infos.firstOrNull { it.flavor == flavor && it.buildType == "debug" }
            val release = infos.firstOrNull { it.flavor == flavor && it.buildType == "release" }
            val applicationId = (release ?: debug ?: infos.first { it.flavor == flavor }).applicationId.get()
            lines += "${key}_APPLICATION_ID=$applicationId"

            firebaseClients(flavor)?.let { clients ->
                val appId = clients[applicationId]
                    ?: throw GradleException("google-services.json has no client for $applicationId (flavour $flavor). Add the app in Firebase and download the file again.")
                lines += "${key}_FIREBASE_APP_ID=$appId"
            }

            // AGP's output layout: apk/<flavor>/<buildType> (apk/<buildType> without flavours),
            // bundle/<variant>, mapping/<variant>.
            val apkFlavorDir = if (flavor == NO_FLAVOR) "" else "$flavor/"
            debug?.let { v ->
                val cap = v.variantName.replaceFirstChar { it.uppercase() }
                lines += "${key}_DEBUG_ASSEMBLE_TASK=assemble$cap"
                lines += "${key}_DEBUG_TEST_TASK=test${cap}UnitTest"
                lines += "${key}_DEBUG_UI_TEST_TASK=connected${cap}AndroidTest"
                lines += "${key}_DEBUG_VARIANT=${v.variantName}"
                lines += "${key}_DEBUG_APK_DIR=$moduleDir/build/outputs/apk/${apkFlavorDir}debug"
            }
            release?.let { v ->
                val cap = v.variantName.replaceFirstChar { it.uppercase() }
                lines += "${key}_RELEASE_BUNDLE_TASK=bundle$cap"
                lines += "${key}_RELEASE_ASSEMBLE_TASK=assemble$cap"
                lines += "${key}_RELEASE_BUNDLE_DIR=$moduleDir/build/outputs/bundle/${v.variantName}"
                lines += "${key}_RELEASE_APK_DIR=$moduleDir/build/outputs/apk/${apkFlavorDir}release"
                lines += "${key}_RELEASE_MAPPING=$moduleDir/build/outputs/mapping/${v.variantName}/mapping.txt"
            }
        }
        lines.forEach { println(it) }
    }
}
