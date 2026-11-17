plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = false
            enableV4Signing = false
        }
    }
    namespace = "com.natatometry.hellowatch"
    compileSdk {
        version = release(36)
    }

    defaultConfig {
        applicationId = "com.natatometry.hellowatch"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

    }

    // One APK per test watch. Same applicationId for both, as the Data Layer requires.
    // watchW projects with GRAVITY+MAG, watchH with ROTATION_VECTOR (from M1/M2 on).
    flavorDimensions += "watch"
    productFlavors {
        create("watchW") {
            dimension = "watch"
            buildConfigField("String", "WATCH_ID", "\"W\"")
            buildConfigField("String", "PROJECTION_METHOD", "\"GRAVITY_MAG\"")
        }
        create("watchH") {
            dimension = "watch"
            buildConfigField("String", "WATCH_ID", "\"H\"")
            buildConfigField("String", "PROJECTION_METHOD", "\"ROTATION_VECTOR\"")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    useLibrary("wear-sdk")
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation("com.google.android.gms:play-services-wearable:17.1.0")

    implementation(libs.play.services.wearable)
    implementation(platform(libs.compose.bom))
    implementation(libs.ui)
    implementation(libs.ui.graphics)
    implementation(libs.ui.tooling.preview)
    implementation(libs.compose.material)
    implementation(libs.compose.foundation)
    implementation(libs.wear.tooling.preview)
    implementation(libs.activity.compose)
    implementation(libs.core.splashscreen)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.ui.test.junit4)
    debugImplementation(libs.ui.tooling)
    debugImplementation(libs.ui.test.manifest)
}