package com.aaatech.buildtools

// Which ../key/*.properties file the aaatech.app-conventions plugin's release signing
// config is loaded from. V2 is the default for every app - only override when an app
// specifically needs to sign with an older key.
enum class SigningKeyVersion(val propertiesFileName: String) {
    V1("keystore1.properties"),
    V2("keystore.properties"),
}

open class AppConfigExtension {
    var signingKeyVersion: SigningKeyVersion = SigningKeyVersion.V2

    // Locale qualifiers (e.g. "en", "ta") to keep when packaging - every other
    // locale's resources (this app's own translated strings included) get stripped
    // during resource linking. Empty (the default) means no filtering at all: every
    // locale ships, including whatever translations are bundled in dependencies
    // (AndroidX, Play Services, etc.) - safe by default, since an app that forgets
    // to list a locale it actually supports would otherwise silently lose it from
    // the packaged app. Only set this if you specifically want the smaller APK that
    // comes from dropping dependency-only locales - and list every locale this app's
    // own resources use, or the same silent-strip bug bites again.
    var supportedLocales: List<String> = emptyList()
}
