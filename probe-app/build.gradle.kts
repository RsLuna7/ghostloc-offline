plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ghostloc.probe"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ghostloc.probe"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

