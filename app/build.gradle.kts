plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mm.smartcgm"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mm.smartcgm"
        minSdk = 26
        targetSdk = 35
        // CI passes its run number so every build installs over the previous one (AGENTS.md P3-2).
        val ci = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        versionCode = ci ?: 1
        versionName = "0.2.0" + (ci?.let { ".$it" } ?: "-local")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Signed with the debug key so the CI APK installs directly; use a real key before store release.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

dependencies {
    implementation("com.mm.smartcgm:core:1.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // P3-3: Robolectric tests for Repo.ingest, the broadcast receiver and the screens (no device needed).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core-ktx:1.6.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
