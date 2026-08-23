plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("kotlin-kapt")
}

android {
    namespace = "com.jeremy.localai"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.jeremy.localai"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        
        ndk {
            abiFilters.addAll(setOf("arm64-v8a"))
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
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")

    // Compose BOM
    val composeBom = platform("androidx.compose:compose-bom:2024.04.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")

    // Room Database dependencies
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    kapt("androidx.room:room-compiler:$roomVersion")

    // Local GGUF Native Inference Wrapper
    implementation("org.codeshipping:llama-kotlin-android:0.1.0")
        // Google AI Edge LiteRT Core & CompiledModel Runtime
    implementation("com.google.ai.edge.litert:litert:2.2.0")

    // LiteRT-LM (Language Model runtime for .litertlm / generative tasks)
    implementation("com.google.ai.edge.litertlm:litertlm-android:latest.release")

}
