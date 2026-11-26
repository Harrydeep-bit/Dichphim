plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.quang.dichphim"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.quang.dichphim"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("fixed") {
            storeFile = file("dichphim.jks")
            storePassword = "dichphim123"
            keyAlias = "dichphim"
            keyPassword = "dichphim123"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("fixed") }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
