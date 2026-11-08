plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.gestsp.mobile"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.gestsp.mobile"
        minSdk = 29          // Android 10 et plus
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signature de débogage : APK installable directement (diffusion interne).
            // Pour le Play Store ou un MDM, remplacer par une clé de l'organisation.
            signingConfig = signingConfigs.getByName("debug")
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

// Aucune dépendance externe : uniquement le SDK Android et la bibliothèque standard Kotlin.
