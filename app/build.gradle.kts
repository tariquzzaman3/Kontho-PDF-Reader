plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.kontho.pdfreader"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.kontho.pdfreader"
        minSdk = 23
        targetSdk = 28
        versionCode = 5
        versionName = "0.5.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
}
