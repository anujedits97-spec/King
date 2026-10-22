import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from environment variables (GitHub Actions secrets) or from
// ~/.myra-phone/signing.properties on the developer's PC. The keystore is never in the repo.
val signing = Properties().apply {
    val f = File(System.getProperty("user.home"), ".myra-phone/signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String): String? = System.getenv(name) ?: signing.getProperty(name)

android {
    namespace = "dev.kairo.myra"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.kairo.myra"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "1.5.6"
    }

    signingConfigs {
        create("release") {
            val store = secret("MYRA_KEYSTORE")
            if (store != null && File(store).exists()) {
                storeFile = File(store)
                storePassword = secret("MYRA_KEYSTORE_PASSWORD")
                keyAlias = secret("MYRA_KEY_ALIAS") ?: "myra"
                keyPassword = secret("MYRA_KEY_PASSWORD") ?: secret("MYRA_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val rel = signingConfigs.getByName("release")
            if (rel.storeFile != null) signingConfig = rel
        }
    }

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging { jniLibs { useLegacyPackaging = true } }
}

dependencies {
    // SSH to your servers (MYRA deploy/test/status recipes).
    implementation("com.hierynomus:sshj:0.38.0")
    // Offline speech recognition, used only to hear the wake word "Myra".
    implementation("net.java.dev.jna:jna:5.13.0@aar")
    implementation("com.alphacephei:vosk-android:0.3.47@aar")
    // Google's QR scanner from Play services, for pairing with the laptop. Needs no camera permission.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.android.gms:play-services-base:18.5.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")
    // Talks to api.github.com (create a repo, push a zip). The system HttpURLConnection cannot send PATCH.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // FileProvider, so the File manager can open and share files with other apps.
    implementation("androidx.core:core:1.13.1")
    // Reads text off a screenshot (English and Hindi/Devanagari) on the phone itself, for screen_ocr.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.mlkit:text-recognition-devanagari:16.0.1")
}

// Two Bouncy Castle jars (1.72 and 1.75) ship the same classes, which breaks compileDebugKotlin.
// Keep only the newer one everywhere.
configurations.all {
    exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    resolutionStrategy {
        force("org.bouncycastle:bcprov-jdk18on:1.75")
    }
}
