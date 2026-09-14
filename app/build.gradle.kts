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
        // Set by the release workflow from the tag and the run number, so a
        // release is a tag and not a bump commit. Locally: 0.0.0 (1).
        versionCode = System.getenv("HOMEOSTAT_VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("HOMEOSTAT_VERSION_NAME") ?: "0.0.0"
    }

    // The release key lives in GitHub secrets, decoded to a file by the
    // workflow. Without it a release build is simply unsigned, which is
    // what a local `assembleRelease` gets.
    val keystore = System.getenv("HOMEOSTAT_KEYSTORE")
    if (keystore != null) {
        signingConfigs {
            create("release") {
                storeFile = file(keystore)
                storePassword = System.getenv("HOMEOSTAT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("HOMEOSTAT_KEY_ALIAS")
                keyPassword = System.getenv("HOMEOSTAT_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
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
    // Material 3 for the theme; AppCompat is what it inflates through.
    implementation(libs.material)
    implementation(libs.appcompat)

    testImplementation(libs.junit)
}
