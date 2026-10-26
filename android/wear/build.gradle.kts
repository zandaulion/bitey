import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * Bitey on the watch: today's total, quick bites and repeat meals, two tiles
 * and two complications. It keeps no diary of its own. Everything it shows
 * comes from the phone over the Data Layer, and a tap asks the phone to log.
 *
 * The Data Layer only connects apps that share a package name and a signing
 * key, so this signs exactly as :app does: the same release inputs, and the
 * same optional sideload key for debug builds. A watch build signed any other
 * way installs, but never hears from the phone.
 */
val signingPropertiesFile = rootProject.file("keystore.properties")
val localSigningProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
}

fun signingValue(property: String, injected: String, environment: String): String? =
    providers.gradleProperty("android.injected.signing.$injected").orNull?.takeIf { it.isNotBlank() }
        ?: System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "store.file", "BITEY_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "store.password", "BITEY_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "key.alias", "BITEY_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "key.password", "BITEY_KEY_PASSWORD")
val releaseSigningReady = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .none { it.isNullOrBlank() }

val debugSigningFile = rootProject.file("debug-keystore.properties")
val debugSigning = Properties().apply {
    if (debugSigningFile.isFile) debugSigningFile.inputStream().use(::load)
}

android {
    namespace = "com.zandaulion.bitey.wear"
    // Wear Compose 1.7 will not compile against less. Only the API level
    // compiled against; targetSdk stays with :app's.
    compileSdk = 37

    defaultConfig {
        // The phone app's package: the Data Layer pairs the two by it.
        applicationId = "com.zandaulion.bitey"
        // Wear OS 3, the first version with tiles and complications as used here.
        minSdk = 30
        targetSdk = 36
        // Its own numbers: Play needs every bundle in a listing to have a
        // different versionCode, and the watch ships on its own track.
        versionCode = 1_000_001
        versionName = "1.0.0"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    if (debugSigningFile.isFile) {
        signingConfigs.getByName("debug") {
            fun required(name: String): String = requireNotNull(debugSigning.getProperty(name)) {
                "Missing $name in debug-keystore.properties"
            }
            storeFile = rootProject.file(required("storeFile"))
            storePassword = required("storePassword")
            keyAlias = required("keyAlias")
            keyPassword = required("keyPassword")
        }
    }

    if (releaseSigningReady) {
        signingConfigs {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseStoreFile))
                storePassword = requireNotNull(releaseStorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
        buildTypes {
            getByName("release") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    buildFeatures {
        compose = true
    }
}

tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            check(releaseSigningReady) {
                "Release signing is not configured; the watch app signs with the same inputs as :app."
            }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.7.1")
    implementation("androidx.wear.compose:compose-foundation:1.7.1")

    implementation("androidx.wear.tiles:tiles:1.6.2")
    implementation("androidx.wear.protolayout:protolayout:1.4.2")
    implementation("androidx.wear.protolayout:protolayout-material:1.4.2")
    implementation("androidx.concurrent:concurrent-futures:1.3.0")
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.3.0")

    implementation("com.google.android.gms:play-services-wearable:20.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")

    // org.json is only a stub in android.jar; the JVM tests need the real one.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260814")
}
