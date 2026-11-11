plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "fr.phoenixpro.meteo"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.phoenixpro.meteo"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Mot de passe de la clé Phoenix Pro : lu depuis le secret GitHub PHOENIX_KEY_PASSWORD (jamais écrit dans le code).
    // Sans ce secret, l'APK est signé avec une clé de test.
    val keyPassword: String? = System.getenv("PHOENIX_KEY_PASSWORD")?.takeIf { it.isNotBlank() }

    signingConfigs {
        if (keyPassword != null) {
            create("phoenix") {
                storeFile = rootProject.file("signature/phoenix.jks")
                storePassword = keyPassword
                keyAlias = "phoenix"
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (keyPassword != null) signingConfigs.getByName("phoenix") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
