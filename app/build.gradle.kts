import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.TaskAction

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val bundledModelsOutput = layout.buildDirectory.dir("generated/bundledModels")
val requiredModelSizes = mapOf(
    "embeddinggemma-2-Q8_0.gguf" to 309_855_456L,
    "mmproj-embeddinggemma-2-Q8_0.gguf" to 554_821_024L,
)
val requireBundledModels = providers.gradleProperty("requireBundledModels")
    .map(String::toBoolean)
    .getOrElse(false)

abstract class BundleModelsTask : DefaultTask() {
    @get:InputDirectory
    @get:Optional
    abstract val modelDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Input
    abstract val modelSizes: MapProperty<String, Long>

    @get:Input
    abstract val requireModels: Property<Boolean>

    @TaskAction
    fun bundle() {
        val output = outputDir.get().asFile
        output.deleteRecursively()
        val source = modelDir.orNull?.asFile
        val sizes = modelSizes.get()
        val missing = sizes.filter { (name, bytes) ->
            val file = source?.resolve(name)
            file == null || !file.isFile || file.length() != bytes
        }.keys
        if (missing.isNotEmpty()) {
            if (requireModels.get()) {
                throw GradleException(
                    "Bundled models are required but missing or incomplete in models/: ${missing.joinToString()}",
                )
            }
            logger.warn("Models are not bundled; missing or incomplete: ${missing.joinToString()}")
            return
        }

        val models = output.resolve("models").apply { mkdirs() }
        for (name in sizes.keys) {
            source!!.resolve(name).copyTo(models.resolve(name), overwrite = true)
        }
        models.resolve("bundled.txt").writeText(sizes.entries.joinToString("\n") { "${it.key} ${it.value}" })
    }
}

val bundleModels = tasks.register<BundleModelsTask>("bundleModels") {
    modelDir.set(rootProject.layout.projectDirectory.dir("models"))
    outputDir.set(bundledModelsOutput)
    modelSizes.set(requiredModelSizes)
    requireModels.set(requireBundledModels)
}

android {
    namespace = "com.digup.android"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.digup.android"
        minSdk = 29
        targetSdk = 35
        versionCode = 4
        versionName = "1.1.0"

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

    androidResources {
        noCompress += "gguf"
    }

    sourceSets.getByName("main").assets.srcDir(bundledModelsOutput)

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

tasks.named("preBuild").configure { dependsOn(bundleModels) }

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
