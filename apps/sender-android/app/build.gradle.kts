plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.airferry.sender"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.airferry.sender"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.2.8"

        ndk {
            abiFilters += listOf("arm64-v8a")
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

    ndkVersion = "27.0.12077973"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Rebuild libtransfer_engine.so from the workspace core on every APK, same
// safeguard as the scanner: a stale JNI lib would lack senderCreate / nextQr.
val compileRustJni = tasks.register<Exec>("compileRustJni") {
    group = "build"
    description = "Compile the Rust transfer_engine JNI library (.so) via cargo-ndk."
    workingDir = rootProject.file("../..")
    doFirst {
        for (v in listOf("ANDROID_NDK_HOME", "ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            val value = System.getenv(v)
            if (!value.isNullOrBlank()) {
                environment(v, value)
            }
        }
        val cargoBin = (System.getenv("CARGO_HOME") ?: "${System.getProperty("user.home")}/.cargo") + "/bin"
        val path = environment["PATH"] ?: System.getenv("PATH") ?: ""
        environment["PATH"] = "$cargoBin:$path"
    }
    commandLine(
        "cargo", "ndk", "-t", "arm64-v8a",
        "-o", rootProject.file("app/src/main/jniLibs").absolutePath,
        "build", "-p", "transfer-engine", "--features", "jni", "--release",
    )
}
tasks.matching {
    it.name.startsWith("merge") && it.name.endsWith("JniLibFolders")
}.configureEach {
    dependsOn(compileRustJni)
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.4")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    testImplementation("junit:junit:4.13.2")
}
