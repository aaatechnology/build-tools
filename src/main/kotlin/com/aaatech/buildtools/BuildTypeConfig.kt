package com.aaatech.buildtools

import com.android.build.api.dsl.ApplicationBuildType
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.extra

// Kotlin extension properties so an app's own build.gradle.kts can write
// debug { appName = "..."; enableLog = true; printLog = true } directly, instead of
// repeating buildConfigField("String", "APP_NAME", ...) etc. per build type.
// ApplicationBuildType doesn't declare ExtensionAware in its own type (AGP's newer
// DSL interfaces deliberately don't), but the actual object Gradle hands you for a
// build type is decorated to implement it at runtime, same as any other
// NamedDomainObjectContainer element - the cast below is safe.
private fun ApplicationBuildType.extensionAware(): ExtensionAware = this as ExtensionAware

var ApplicationBuildType.appName: String
    get() = extensionAware().extra.properties["appName"] as? String ?: ""
    set(value) {
        extensionAware().extra.set("appName", value)
    }

var ApplicationBuildType.enableLog: Boolean
    get() = extensionAware().extra.properties["enableLog"] as? Boolean ?: false
    set(value) {
        extensionAware().extra.set("enableLog", value)
    }

var ApplicationBuildType.printLog: Boolean
    get() = extensionAware().extra.properties["printLog"] as? Boolean ?: false
    set(value) {
        extensionAware().extra.set("printLog", value)
    }
