plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "fr.phoenixpro.retouche"
    compileSdk = 34

    defaultConfig {
        applicationId = "fr.phoenixpro.retouche"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = "1.0.$buildNumber"
    }

    signingConfigs {
        // même clé que les autres applis Phoenix Pro : chaque nouvelle version s'installe par-dessus l'ancienne
        create("phoenix") {
            storeFile = rootProject.file("signature/phoenix.jks")
            storePassword = "49080e47f06ab1f0d2263a2f"
            keyAlias = "phoenix"
            keyPassword = "49080e47f06ab1f0d2263a2f"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("phoenix")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.webkit:webkit:1.11.0")
}
