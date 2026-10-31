import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Keep local signing paths out of source control. Unconfigured checkouts use the normal SDK
// debug key; distributing an update requires the same debug key as the previously shipped APK.
val localBuildProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
val debugKeystorePath = localBuildProperties.getProperty("debug.keystore")
// 只打需要的 ABI：onnxruntime 与 mlkit 的 .so 每个 ABI 约 30 MB，四份就是 110 MB+。
// 默认 x86_64 对应 MuMu 模拟器（ro.product.cpu.abilist 首位）；真机在 local.properties 里写
// abi.filters=arm64-v8a 即可，无需改这个文件。多个 ABI 用逗号分隔。
val packagedAbiFilters = (localBuildProperties.getProperty("abi.filters") ?: "x86_64")
    .split(',').map(String::trim).filter(String::isNotEmpty)

android {
    namespace = "com.landosol.toolbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.landosol.toolbox"
        minSdk = 26
        targetSdk = 35
        // major*10000 + minor*100 + patch, so the code stays monotonic as the name moves.
        versionCode = 10018
        versionName = "1.0.18"

        ndk {
            abiFilters += packagedAbiFilters
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            if (!debugKeystorePath.isNullOrBlank()) {
                storeFile = rootProject.file(debugKeystorePath)
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
        getByName("test").resources.srcDir("$rootDir/test-fixtures/protocol")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.mlkit.text.recognition.chinese)
    implementation(libs.onnxruntime.android)
    ksp(libs.androidx.room.compiler)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
