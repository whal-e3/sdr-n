plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val uploadSigningValues = listOf(
    "ORBIT_UPLOAD_KEYSTORE", "ORBIT_UPLOAD_KEY_ALIAS",
    "ORBIT_UPLOAD_STORE_PASSWORD", "ORBIT_UPLOAD_KEY_PASSWORD",
).associateWith { providers.environmentVariable(it).orNull }
val hasUploadSigning = uploadSigningValues.values.any { it != null }
if (hasUploadSigning) {
    val missing = uploadSigningValues.filterValues { it.isNullOrBlank() }.keys
    require(missing.isEmpty()) { "Missing upload signing environment variables: ${missing.joinToString()}" }
    require(file(uploadSigningValues.getValue("ORBIT_UPLOAD_KEYSTORE")!!).isFile) {
        "Upload keystore file does not exist"
    }
}

android {
    namespace = "org.satelliteeavesdropper.app"
    compileSdk = 36
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "org.satelliteeavesdropper.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-Wextra")
            }
        }
        buildConfigField("String", "CATALOG_BASE_URL", "\"${providers.gradleProperty("catalogBaseUrl").orElse("").get()}\"")
        buildConfigField("String", "CATALOG_PUBLIC_KEY_BASE64", "\"${providers.gradleProperty("catalogPublicKeyBase64").orElse("").get()}\"")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasUploadSigning) {
            create("upload") {
                storeFile = file(uploadSigningValues.getValue("ORBIT_UPLOAD_KEYSTORE")!!)
                keyAlias = uploadSigningValues.getValue("ORBIT_UPLOAD_KEY_ALIAS")
                storePassword = uploadSigningValues.getValue("ORBIT_UPLOAD_STORE_PASSWORD")
                keyPassword = uploadSigningValues.getValue("ORBIT_UPLOAD_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        getByName("release") {
            if (hasUploadSigning) signingConfig = signingConfigs.getByName("upload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    // Compose 1.9.x is the newest line compatible with AGP 8.13 / compileSdk 36.
    val composeBom = platform("androidx.compose:compose-bom:2025.09.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.orekit:orekit:12.1.2")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.google.code.gson:gson:2.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
