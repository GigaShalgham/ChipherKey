import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Release signing: reads a properties file whose path comes from the
// CK_KEYSTORE_PROPERTIES environment variable (maintainer machines) or
// falls back to keystore.properties next to settings.gradle.kts
// (gitignored). CI has neither, so CI release builds stay unsigned —
// only maintainer-published GitHub Releases carry signed artifacts.
val keystorePropertiesFile: File = System.getenv("CK_KEYSTORE_PROPERTIES")
    ?.let { File(it) }
    ?: rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "org.cipherkey.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.cipherkey"
        minSdk = 26
        targetSdk = 36
        versionCode = 14
        versionName = "0.6.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = File(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // v0.1.0-alpha: minification intentionally disabled. No on-device
            // verification exists yet, and R8/resource-shrinking would add an
            // unverified transformation layer on top of an unverified build.
            // Re-evaluate once instrumented/smoke testing exists (tracked in
            // the engineering report, remaining work).
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            // NOTE: without a signing config, release builds are unsigned.
        }
    }

    buildFeatures {
        compose = true
        // VERSION_NAME is surfaced to the About card via BuildConfig
        // (deep-scan finding 7: the hardcoded "0.1.0" there was stale).
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:crypto"))
    implementation(project(":core:keys"))
    implementation(project(":core:messages"))
    implementation(project(":core:sessions"))
    implementation(project(":security:policies"))
    implementation(project(":security:storage"))
    implementation(project(":security:clipboard"))
    implementation(project(":data:database"))
    implementation(project(":data:preferences"))
    implementation(project(":data:repository"))
    // :keyboard deliberately NOT included (spec 003, FR-006): the shipped
    // app no longer registers an IME service, removing the Compose-in-IME
    // crash class entirely. The module stays in settings.gradle.kts and
    // keeps compiling in CI; it may return behind a flag in a future spec.
    implementation(project(":plugins:api"))
    implementation(project(":plugins:registry"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    // v0.3.0: system fingerprint prompt + its FragmentActivity host.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.hilt.navigation.compose)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
