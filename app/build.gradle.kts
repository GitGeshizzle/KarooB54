plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.gitgeshizzle.karoob54"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.gitgeshizzle.karoob54"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

dependencies {
    // Hammerhead Karoo Extension SDK (from GitHub Packages, see settings.gradle.kts)
    implementation("io.hammerhead:karoo-ext:1.1.9")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Pure JVM test of the protocol decoder (no device/emulator needed): ./gradlew test
    testImplementation("junit:junit:4.13.2")
}
