plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.raj.ncscan"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.raj.ncscan"
        minSdk = 23
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
    }

    // CI (GitHub Actions) menandatangani dengan keystore rilis dari Secrets;
    // build lokal tanpa env tsb otomatis fallback ke debug keystore.
    val ciKeystore = System.getenv("KEYSTORE_PATH")
    signingConfigs {
        if (ciKeystore != null) {
            create("release") {
                storeFile = file(ciKeystore)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false // sideload internal, tanpa R8
            signingConfig = if (ciKeystore != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    implementation("com.squareup.okhttp3:okhttp:5.4.0")
    implementation("androidx.work:work-runtime:2.11.2")
}
