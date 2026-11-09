plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.phoenixpro.photos"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.phoenixpro.photos"
        minSdk = 28
        targetSdk = 34
        versionCode = 3
        versionName = "1.2.0"
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    signingConfigs {
        // clé fixe : permet d'installer chaque nouvelle version par-dessus l'ancienne
        create("phoenix") {
            storeFile = rootProject.file("signature/phoenix.jks")
            storePassword = "49080e47f06ab1f0d2263a2f"
            keyAlias = "phoenix"
            keyPassword = "49080e47f06ab1f0d2263a2f"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("phoenix")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    androidResources { noCompress += listOf("onnx") }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("org.opencv:opencv:4.10.0")
    implementation("org.osmdroid:osmdroid-android:6.1.18")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
