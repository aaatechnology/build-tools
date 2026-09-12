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
}
