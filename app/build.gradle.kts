plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chapterreader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.chapterreader.app"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "1." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }

    // A fixed key so each new build installs over the previous one.
    signingConfigs {
        create("private") {
            storeFile = file("reader.keystore")
            storePassword = "chapterreader"
            keyAlias = "reader"
            keyPassword = "chapterreader"
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
    implementation("org.jsoup:jsoup:1.17.2")
}
