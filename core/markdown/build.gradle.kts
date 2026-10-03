plugins {
  alias(libs.plugins.android.library)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.memoh.core.markdown"
  compileSdk = 37
  defaultConfig { minSdk = 26 }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures { compose = true }
}

kotlin {
  compilerOptions {
    jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
  }
}

dependencies {
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.core.ktx)
  implementation(project(":core:designsystem"))
  implementation(libs.androidx.compose.runtime)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.foundation)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.commonmark)
  implementation(libs.commonmark.ext.gfm.tables)
  implementation(libs.commonmark.ext.gfm.strikethrough)
  implementation(libs.commonmark.ext.autolink)
  implementation(libs.commonmark.ext.task.list.items)
  debugImplementation(libs.androidx.compose.ui.tooling)
  implementation(libs.androidx.compose.ui.tooling.preview)
  testImplementation(libs.junit)
}
