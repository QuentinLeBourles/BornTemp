import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Hand-editable base; the git short SHA, a -dirty flag, and a build timestamp
// are appended automatically so every build is uniquely identifiable both
// in-app and via `adb shell dumpsys package com.borntemp.app | grep versionName`.
val baseVersion = "1.0.0"

fun git(vararg args: String): String? = try {
    val out = providers.exec {
        commandLine("git", *args)
        isIgnoreExitValue = true
    }
    if (out.result.get().exitValue == 0) out.standardOutput.asText.get().trim() else null
} catch (_: Exception) { null }

val gitSha = git("rev-parse", "--short", "HEAD")
val gitDirty = (git("status", "--porcelain")?.isNotBlank()) ?: false
val gitCount = git("rev-list", "--count", "HEAD")?.toIntOrNull()
val buildStamp: String = SimpleDateFormat("yyyy-MM-dd HH:mm").format(Date())
val computedVersionName =
    if (gitSha != null) "$baseVersion-$gitSha${if (gitDirty) "-dirty" else ""} ($buildStamp)"
    else "$baseVersion-nogit ($buildStamp)"
val computedVersionCode = gitCount ?: 1

android {
    namespace = "com.borntemp.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.borntemp.app"
        minSdk = 26
        targetSdk = 34
        versionCode = computedVersionCode
        versionName = computedVersionName
    }

    buildTypes {
        release {
            isMinifyEnabled = true
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
        compose = true
        buildConfig = true   // exposes BuildConfig.VERSION_NAME for the in-app footer
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.13"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
