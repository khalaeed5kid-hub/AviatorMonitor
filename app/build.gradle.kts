plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aviatortracker"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.aviatortracker"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
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
    // OCR محلي (النموذج مضمّن في التطبيق، لا يحتاج إنترنت)
    implementation("com.google.mlkit:text-recognition:16.0.0")
    testImplementation("junit:junit:4.13.2")
}
