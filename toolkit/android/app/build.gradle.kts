plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.stafftoolkit.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.stafftoolkit.app"
        minSdk = 29
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // A fixed key so each new build installs over the previous one.
    signingConfigs {
        create("private") {
            storeFile = file("toolkit.keystore")
            storePassword = "stafftoolkit"
            keyAlias = "toolkit"
            keyPassword = "stafftoolkit"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("private")
        }
        getByName("debug") {
            signingConfig = signingConfigs.getByName("private")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    // On-device text recognition, to read Bedrock chat off the screen (works offline)
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
