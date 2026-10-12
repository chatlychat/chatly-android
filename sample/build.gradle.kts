plugins {
    id("com.android.application") version "8.4.0"
    id("org.jetbrains.kotlin.android") version "2.0.21"
}

android {
    namespace = "com.chatly.sample"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chatly.sample"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { viewBinding = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(project(":chatly"))
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
