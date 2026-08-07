import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // AGP 9+ has built-in Kotlin support — no separate Kotlin plugin needed.
    id("com.android.application")
}

// --- Versioning -------------------------------------------------------------
// The release workflow pushes a git tag like "v0.2.0"; GitHub Actions exposes it
// as GITHUB_REF_NAME (with GITHUB_REF_TYPE == "tag"). We derive the app version
// from that tag so the APK's version always matches the release and can never
// drift. Outside a tag build (local dev, CI on main) we fall back to a dev
// placeholder so normal builds keep working.
val releaseTag: String? = System.getenv("GITHUB_REF_NAME")
    ?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }

// Map a semver string to a monotonically increasing integer: 0.2.0 -> 200,
// 1.0.0 -> 10000. Tolerates pre-release suffixes like "0.2.0-rc1" (ignored).
fun semverToVersionCode(v: String): Int {
    val (major, minor, patch) = (v.split("-", limit = 2)[0].split(".") + listOf("0", "0", "0"))
        .take(3).map { it.toIntOrNull() ?: 0 }
    return major * 10_000 + minor * 100 + patch
}

val appVersionName: String = releaseTag ?: "0.1.0-dev"
val appVersionCode: Int = releaseTag?.let(::semverToVersionCode) ?: 1

android {
    namespace = "io.github.gitgeshizzle.karoob54"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.gitgeshizzle.karoob54"
        minSdk = 23
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
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

    sourceSets["main"].java.srcDirs("src/main/kotlin")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Hammerhead Karoo Extension SDK (from GitHub Packages, see settings.gradle.kts)
    implementation("io.hammerhead:karoo-ext:1.1.9")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.jakewharton.timber:timber:5.0.1")

    // Pure JVM test of the protocol decoder (no device/emulator needed): ./gradlew test
    testImplementation("junit:junit:4.13.2")
}
