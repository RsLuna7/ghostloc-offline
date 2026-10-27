plugins {
    id("com.android.library")
}

android {
    namespace = "dev.vectorlab.stubs"
    compileSdk = 36

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    compileOnly("androidx.annotation:annotation:1.8.1")
    compileOnly("io.github.libxposed:annotation:1.0.0")
}
