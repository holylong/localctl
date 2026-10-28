plugins {
    id("com.android.library")
}

android {
    namespace = "com.hexdeploy.facesdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")

        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))  // RK3588 是 arm64-v8a
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments("-DANDROID_STL=c++_shared")
                // 允许未定义的符号（RKNN 运行时加载）
                arguments("-DANDROID_ALLOW_UNDEFINED_SYMBOLS=ON")
            }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
}
