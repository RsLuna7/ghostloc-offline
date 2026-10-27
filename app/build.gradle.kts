plugins {
    id("com.android.application")
}

android {
    namespace = "dev.ghostloc.offline"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.ghostloc.offline"
        minSdk = 28
        targetSdk = 35
        versionCode = 7
        versionName = "0.4.3"
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

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(project(":vector-api-stubs"))
    implementation("io.github.libxposed:service:102.0.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
}
