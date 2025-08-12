import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

val localProperties = Properties().apply {
    load(rootProject.file("local.properties").inputStream())
}
val apiKey = localProperties["MY_API_KEY"] as String

android {
    namespace = "com.example.aibooo"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.aibooo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "API_KEY", "\"$apiKey\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation(libs.core.ktx)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)

    // Gemini AI
    implementation(libs.generativeai)
    implementation(libs.activity)
    implementation(libs.cardview)

    // Markdown
    implementation(libs.core)
    implementation(libs.html)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)

    implementation(libs.glide)
    implementation(libs.lottie)

    implementation("androidx.camera:camera-core:1.5.0-beta02")
    implementation("androidx.camera:camera-camera2:1.5.0-beta02")
    implementation("androidx.camera:camera-lifecycle:1.5.0-beta02")
    implementation("androidx.camera:camera-view:1.5.0-beta02")
    implementation("com.github.binarybeam:Prexo-Ai:1.1.0")
    implementation("com.github.binarybeam:Prexocore:1.5.5")
}