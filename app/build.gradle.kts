plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ghostloc.offline"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.ghostloc.offline"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    compileOnly(project(":vector-api-stubs"))
    implementation("io.github.libxposed:service:101.0.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
}

