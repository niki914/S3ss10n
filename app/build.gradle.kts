plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.android.compose)}

android {
    namespace = "com.niki914.demo.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.niki914.demo"
        minSdk = 26
        targetSdk = 34
        versionName = "2.1.5"
        versionCode = 4
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // 指定你想要支持的ABIs
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }
    buildFeatures {
        buildConfig = true
        compose = true
    }

    lint { checkReleaseBuilds = false }
}

dependencies {
    implementation(project(":composebase"))
    implementation(project(":s3ss10n"))
//    implementation("com.github.niki914:s3ss10n:2.1.5")

    // Radius
    implementation("com.github.Kyant0:Capsule:2.1.0")

    // Backdrop (AndroidLiquidGlass)
    implementation(libs.backdrop)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Material & AndroidX
    implementation(libs.google.material)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.window)
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.bundles.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.compose.ui.tooling.preview)

    // Zephyr
    implementation(libs.bundles.zephyr)
}
