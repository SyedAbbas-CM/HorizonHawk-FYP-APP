import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    kotlin("android")
    kotlin("kapt")
}

android {
    compileSdk = 34
    namespace = "com.example.hhawk4"
    defaultConfig {
        applicationId = "com.example.hhawk4"
        minSdk = 21
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
        multiDexEnabled = true

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        getByName("debug") {
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    compileOptions {
        sourceCompatibility(JavaVersion.VERSION_1_8)
        targetCompatibility(JavaVersion.VERSION_1_8)
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Specific setup for Jetpack Compose
    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1" // Use the appropriate version compatible with your Kotlin and Compose version
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs = listOf("-Xallow-jvm-ir-dependencies", "-Xskip-prerelease-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.9.1")

    // Compose Dependencies
    implementation("androidx.compose.ui:ui:1.6.7")
    implementation("androidx.compose.material:material:1.6.7")
    implementation("androidx.compose.material:material:1.6.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // DJI SDK
    implementation("com.dji:dji-sdk:4.17")
    implementation("com.dji:dji-uxsdk:4.13")
    implementation(libs.androidx.material3.android)
    implementation(libs.androidx.monitor)
    implementation(libs.androidx.junit.ktx)
    compileOnly("com.dji:dji-sdk-provided:4.17")
    implementation 'com.google.android.gms:play-services-maps:17.0.0'

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.0-native-mt")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.0-native-mt")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.3.0")
    // ButterKnife
    implementation("com.jakewharton:butterknife:10.2.3")
    kapt("com.jakewharton:butterknife-compiler:10.2.3")

    implementation("androidx.multidex:multidex:2.0.1")
}