plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.minrray.voicectl"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.minrray.voicectl"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "arm64-v8a"
        }
    }
    signingConfigs {
        create("release") {
            storeFile = file("platform.jks")
            storePassword = "123456"
            keyAlias = "platform"
            keyPassword = "123456"
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlinOptions {
        jvmTarget = "21"
        freeCompilerArgs = listOf("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.okhttp)
    
    implementation(project(":voice-engine-sdk"))
    implementation(project(":facesdk"))
    implementation(files("libs/opus-release.aar"))
    implementation(files("libs/QWeather_Public_Android_V5.2.1.jar"))
    
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("net.i2p.crypto:eddsa:0.3.0")
    
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.28")
    implementation("com.caverock:androidsvg-aar:1.4")
    implementation(libs.androidx.appcompat)
    // 添加约束布局依赖
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.constraintlayout:constraintlayout-compose:1.1.0")
    
    // CameraX dependencies for face recognition
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:${cameraxVersion}")
    implementation("androidx.camera:camera-camera2:${cameraxVersion}")
    implementation("androidx.camera:camera-lifecycle:${cameraxVersion}")
    implementation("androidx.camera:camera-view:${cameraxVersion}")
    
    // MediaPipe Face Mesh for face direction detection
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    implementation("pl.droidsonroids.gif:android-gif-drawable:1.2.28")
}