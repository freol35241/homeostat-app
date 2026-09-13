plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.homeostat.companion"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.homeostat.companion"
        // Both target phones (Galaxy S22, Galaxy S25) shipped on API 31 or
        // later, and the permission story for background location and
        // foreground services is materially simpler from 31 up. There is no
        // older device to support, so there is no reason to carry the
        // compatibility code for one.
        minSdk = 31
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // MQTT 3.1.1 with persistent sessions and a last will, pure JVM. The
    // reconnect logic is ours (MqttSession), not Paho's, so it is testable.
    implementation(libs.paho.mqttv3)
    // The provisioning blob is TOML (companion-protocol.md).
    implementation(libs.tomlj)
    // Google's code scanner: Play services supplies the scanner UI, so the
    // app needs no camera permission and no camera code of its own.
    implementation(libs.play.services.code.scanner)
    // The platform's geofencing: one fence, run by the OS at near-zero cost.
    implementation(libs.play.services.location)

    testImplementation(libs.junit)
}
