plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version comes from the GitHub Actions run number, so every build is identifiable in the app
val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1

android {
    namespace = "com.curiosityengine.app"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.curiosityengine.app"
        minSdk = 19            // Galaxy Tab E SM-T560, Android 4.4.4
        targetSdk = 34
        versionCode = runNumber
        versionName = "0.1.$runNumber"
        multiDexEnabled = true // needed on API 19 once the app grows
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.multidex:multidex:2.0.1")
}
