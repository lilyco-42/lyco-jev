import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: reads a properties file kept OUTSIDE the repo
// (storeFile / storePassword / keyAlias / keyPassword). Override the path with
// the JEV_KEYSTORE_PROPS env var. Without it, release builds are unsigned.
val releaseProps = Properties().apply {
    val path = System.getenv("JEV_KEYSTORE_PROPS") ?: "H:/android/keys/jev-release.properties"
    // java.io.File, NOT Gradle's file(): on a non-Windows host the latter parses
    // "H:/..." as a URL scheme ("Cannot convert URL ... to a file") and throws
    // before the exists() check can skip it. Only release builds need this.
    val f = File(path)   // 'java.io.File' would resolve 'java' to the Gradle Java extension
    if (f.exists()) FileInputStream(f).use { load(it) }
}

android {
    namespace = "com.jev.probe"
    compileSdk = 35

    // Same NDK the llama.cpp arm64 build is pinned to (goal 1, on-device judge).
    ndkVersion = "30.0.15729638"

    // On-device llama.cpp scorer; see app/src/main/cpp/CMakeLists.txt.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    androidResources {
        // The bundled 0.53 GB GGUF barely compresses, so deflating it would cost
        // build time and install time for almost nothing.
        noCompress += "gguf"
    }

    defaultConfig {
        applicationId = "com.jev.probe"
        minSdk = 30
        targetSdk = 35
        versionCode = 7
        versionName = "1.4-lyco.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // ML Kit's bundled Chinese recognizer ships native libs for every ABI.
        // The target phone (and every phone this can run on: minSdk 30) is
        // arm64, so keep only that one — the other three are dead weight.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        // Flags only: `path` belongs to the android-level externalNativeBuild below.
        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
    }

    signingConfigs {
        if (releaseProps.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // The instrumented test that actually executes the on-device judge
            // runs on the x86_64 emulator, so debug carries both ABIs. A
            // distribution build passes -PdistAbi to drop the emulator one:
            // every real phone is arm64 and the x86_64 natives are ~60 MB of
            // the APK.
            ndk {
                abiFilters += if (project.hasProperty("distAbi")) listOf("arm64-v8a")
                else listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    // Uncompressed, page-aligned .so files: required for the 16 KB page-size
    // devices Android 15+ ships, and it lets the loader mmap the ML Kit natives
    // instead of unpacking them at install time.
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    // Real org.json on the unit-test classpath: android.jar's copy is a stub that
    // throws, and the parity test needs JSONObject/JSONArray to behave.
    testImplementation("org.json:json:20240303")

    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    // ActivityScenario, for launching the app's own screens from a test.
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // On-device OCR. The *bundled* Chinese model (not the play-services variant):
    // it works on phones with no Google Play services and needs no model download.
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    // Goal 2: on-device YOLO object detection next to ML Kit OCR. Same runtime
    // jev-wingman uses, so the arm64 .so is already a known quantity on device.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.20.0")
}
