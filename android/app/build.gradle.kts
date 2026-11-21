// Application module build script for Family Kitchen Board (Kotlin 2.2.0 + Jetpack Compose + Material 3).
plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
  id("org.jetbrains.kotlin.plugin.compose")
  id("org.jetbrains.kotlin.plugin.serialization")
}

android {
  namespace = "com.familykitchen.board"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.familykitchen.board"
    // Targets Samsung Galaxy S22 & S22 Ultra running Android 12+ (API 31+).
    minSdk = 31
    targetSdk = 34
    versionCode = 1
    versionName = "1.0.0"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
    }
  }

  buildFeatures {
    compose = true
  }

  testOptions {
    unitTests.isReturnDefaultValues = true
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }

  packaging {
    jniLibs {
      // Required so native OpenCL / LiteRT-LM shared libraries load cleanly on Galaxy S22 GPU.
      useLegacyPackaging = true
    }
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

kotlin {
  compilerOptions {
    jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
  }
}

dependencies {
  // Core AndroidX, Activity, and Lifecycle foundations (pinned for compileSdk = 34 compatibility)
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.activity:activity-ktx:1.9.2")
  implementation("androidx.activity:activity-compose:1.9.2")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.6")
  implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")

  // Jetpack Compose Material 3 UI & Tooling (BOM 2024.10.01)
  implementation(platform("androidx.compose:compose-bom:2024.10.01"))
  implementation("androidx.compose.ui:ui")
  implementation("androidx.compose.ui:ui-graphics")
  implementation("androidx.compose.ui:ui-tooling-preview")
  implementation("androidx.compose.material3:material3")
  implementation("androidx.compose.material:material-icons-extended")
  debugImplementation("androidx.compose.ui:ui-tooling")

  // Coroutines, Strict JSON Serialization, Offline DataStore, and EXIF Image Normalization
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
  implementation("androidx.datastore:datastore-preferences:1.1.1")
  implementation("androidx.exifinterface:exifinterface:1.3.7")

  // On-Device Multimodal & Text LLM Runtimes (LiteRT-LM 0.16.1 primary + MediaPipe GenAI fallback)
  implementation("com.google.ai.edge.litertlm:litertlm-android:0.16.1")
  implementation("com.google.mediapipe:tasks-genai:0.10.22")

  // Hermetic JVM Unit Testing
  testImplementation("junit:junit:4.13.2")
  testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
