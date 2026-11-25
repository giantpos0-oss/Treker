plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.willwalsh.treker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.willwalsh.treker"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
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

    // Valhalla routing engine, running on the phone against the downloaded map's routing tiles.
    implementation("io.github.rallista:valhalla-mobile:0.6.1")
}
