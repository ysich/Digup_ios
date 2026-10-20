plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.digup.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.digup.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 3
        versionName = "1.0.2"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_NATIVE=OFF",
                    "-DLLAMA_BUILD_COMMON=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_BUILD_UI=OFF",
                    "-DLLAMA_CURL=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DMTMD_VIDEO=OFF"
                )
                cppFlags += listOf("-O3", "-fexceptions", "-frtti")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*"
        )
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.sqlite:sqlite-bundled:2.7.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.exifinterface:exifinterface:1.4.1")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")

    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
}
