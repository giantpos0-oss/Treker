plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Treker's Google Maps Platform key comes from the GitHub secret TREKER_GOOGLE_KEY at build time.
// It is never committed. Without it the app still builds: live exploration and map browsing work
// (they use the phone's own address lookup and OpenStreetMap), and Google features say the key is missing.
val googleKey: String = System.getenv("TREKER_GOOGLE_KEY") ?: ""

android {
    namespace = "com.willwalsh.treker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.willwalsh.treker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        manifestPlaceholders["MAPS_API_KEY"] = googleKey
        buildConfigField("String", "GOOGLE_KEY", "\"$googleKey\"")
    }

    // Release builds are signed with Treker's own key (GitHub secret TREKER_SIGNING), so every new
    // version installs over the old one. Without the secret, the debug key is used.
    val keystorePath: String? = System.getenv("TREKER_KEYSTORE")
    signingConfigs {
        if (keystorePath != null) {
            create("treker") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("TREKER_KEY_PASSWORD")
                keyAlias = "treker"
                keyPassword = System.getenv("TREKER_KEY_PASSWORD")
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("treker") ?: signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    // The user guide and release notes (docs/*.md) are bundled so Help works offline.
    sourceSets { getByName("main") { assets.srcDir(rootProject.file("docs")) } }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.05.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-service:2.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")

    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Google's turn-by-turn engine for walking and driving. It includes the Maps SDK,
    // so the Maps SDK must not be added separately.
    implementation("com.google.android.libraries.navigation:navigation:7.9.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")
}
