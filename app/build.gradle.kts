import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.robocallguard"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.robocallguard"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        // Release keystore lives in keystore/ and is configured via the
        // gitignored keystore.properties (see keystore.properties.example).
        val keystoreProps = rootProject.file("keystore.properties")
        if (keystoreProps.exists()) {
            val props = Properties().apply {
                load(FileInputStream(keystoreProps))
            }
            create("release") {
                storeFile = rootProject.file(
                    props.getProperty("storeFile", "keystore/release.keystore")
                )
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Placeholder: falls back to debug signing until keystore.properties exists.
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
    }
}
