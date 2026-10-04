import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
  alias(libs.plugins.hilt)
  alias(libs.plugins.ksp)
}

val releaseKeystoreProperties = Properties().apply {
  val propertiesFile = rootProject.file("keystore.properties")
  if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}

android {
  namespace = "dev.memoh.android"
  compileSdk = 37
  defaultConfig {
    applicationId = "dev.memoh.android"
    minSdk = 26
    targetSdk = 36
    versionCode = 13
    versionName = "0.1.12"
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    vectorDrawables { useSupportLibrary = true }
  }
  signingConfigs {
    if (releaseKeystoreProperties.isNotEmpty()) {
      create("release") {
        storeFile = rootProject.file(releaseKeystoreProperties.getProperty("storeFile"))
        storePassword = releaseKeystoreProperties.getProperty("storePassword")
        keyAlias = releaseKeystoreProperties.getProperty("keyAlias")
        keyPassword = releaseKeystoreProperties.getProperty("keyPassword")
      }
    }
  }
  buildTypes {
    release {
      if (releaseKeystoreProperties.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
  packaging {
    resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
  }
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
  }
}

dependencies {
  implementation(project(":core:model"))
  implementation(project(":core:network"))
  implementation(project(":core:data"))
  implementation(project(":core:designsystem"))
  implementation(project(":core:markdown"))
  implementation(project(":feature:login"))
  implementation(project(":feature:bots"))
  implementation(project(":feature:sessions"))
  implementation(project(":feature:chat"))
  implementation(project(":feature:settings"))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.animation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.graphics.shapes)
  implementation(libs.okhttp)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.hilt.android)
  ksp(libs.hilt.compiler)
  implementation(libs.hilt.navigation.compose)

  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.tooling.preview)

  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.turbine)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.espresso.core)
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
}
