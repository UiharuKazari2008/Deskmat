plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "dev.dexwp.backdrop"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.dexwp.backdrop"
        minSdk = 34
        targetSdk = 36
        versionCode = 18
        versionName = "0.18"
        // The upscaler's ncnn build is arm64 only (every DeX / foldable Galaxy is arm64).
        ndk { abiFilters += "arm64-v8a" }
    }
    ndkVersion = "29.0.14206865"
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    androidResources {
        // ncnn reads the model straight from the APK.
        noCompress += listOf("bin", "param")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // No release key yet: sign with the debug key so releases update existing installs.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.play.services.location)
    implementation(libs.material.kolor)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
}
