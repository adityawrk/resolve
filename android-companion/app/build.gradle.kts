plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.cssupport.companion"
  compileSdk = 35

  defaultConfig {
    applicationId = "com.cssupport.companion"
    minSdk = 28  // Raised to 28 (Android 9) for EncryptedSharedPreferences support.
    targetSdk = 35
    versionCode = 2
    versionName = "0.2.0"

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro",
      )
    }
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
  implementation("androidx.core:core-ktx:1.13.1")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("com.google.android.material:material:1.12.0")
  implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

  // Encrypted SharedPreferences for secure credential storage.
  implementation("androidx.security:security-crypto:1.1.0-alpha06")

  // Layout components used across screens
  implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")
  implementation("androidx.recyclerview:recyclerview:1.3.2")
}
