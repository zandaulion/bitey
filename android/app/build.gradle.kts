import org.gradle.api.tasks.Sync
import java.util.Properties

plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
}

/**
 * Release credentials must remain local. They may come from the ignored
 * android/keystore.properties file or from the process environment, which is
 * how the existing Zandaulion keystore is used for a one-off release build.
 */
val signingPropertiesFile = rootProject.file("keystore.properties")
val localSigningProperties = Properties().apply {
    if (signingPropertiesFile.isFile) signingPropertiesFile.inputStream().use(::load)
}

fun signingValue(property: String, environment: String): String? =
    System.getenv(environment)?.takeIf { it.isNotBlank() }
        ?: localSigningProperties.getProperty(property)?.takeIf { it.isNotBlank() }

val releaseStoreFile = signingValue("storeFile", "BITEY_STORE_FILE")
val releaseStorePassword = signingValue("storePassword", "BITEY_STORE_PASSWORD")
val releaseKeyAlias = signingValue("keyAlias", "BITEY_KEY_ALIAS")
val releaseKeyPassword = signingValue("keyPassword", "BITEY_KEY_PASSWORD")

// Names only. A build log is pasted into chats and issues, so the diagnosis
// says which input is absent and never what any of them contain.
val missingSigningInputs = listOf(
    "storeFile" to releaseStoreFile,
    "storePassword" to releaseStorePassword,
    "keyAlias" to releaseKeyAlias,
    "keyPassword" to releaseKeyPassword,
).filter { (_, value) -> value.isNullOrBlank() }.map { (name, _) -> name }

val releaseSigningReady = missingSigningInputs.isEmpty()

// storeFile is resolved against android/, the root of this Gradle build, so a
// relative path in keystore.properties reads from there rather than from the
// directory the build happened to be started in.
val resolvedStoreFile = releaseStoreFile?.let(rootProject::file)

android {
    namespace = "com.zandaulion.bitey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.zandaulion.bitey"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"
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
}

/**
 * The interface has one source of truth: web/ and core/. At build time they
 * are copied into the APK and served from https://plate.local by WebView. No
 * production app assets are fetched from the old Express server.
 */
val plateRoot = rootProject.projectDir.parentFile
val generatedPlateAssets = layout.buildDirectory.dir("generated/plate-assets")

val syncPlateAssets by tasks.registering(Sync::class) {
    from(plateRoot.resolve("web"))
    from(plateRoot.resolve("core")) { into("core") }
    from(plateRoot.resolve("assets/foods.sqlite")) { into("database") }
    into(generatedPlateAssets)
}

android.sourceSets.named("main") {
    // The copy task is wired to preBuild below, so resolving this directory at
    // configuration time does not lose its generated-asset dependency.
    assets.srcDir(generatedPlateAssets.get().asFile)
}

tasks.named("preBuild") {
    dependsOn(syncPlateAssets)
}

// Never leave an unsigned upload bundle in the build directory where it could
// be mistaken for a Play-ready release. The values live only in the ignored
// keystore.properties file (or the local process environment).
//
// Both failures below name the absolute path they looked at. "Not configured"
// on its own sends you hunting for a file that is usually present but in the
// wrong directory, or saved by Windows as keystore.properties.txt.
tasks.configureEach {
    if (name == "preReleaseBuild") {
        doFirst {
            check(releaseSigningReady) {
                buildString {
                    append("Release signing is not configured. Missing: ")
                    append(missingSigningInputs.joinToString(", "))
                    append(".\n")
                    if (signingPropertiesFile.isFile) {
                        append("Read: ${signingPropertiesFile.absolutePath}\n")
                        append("Add the missing key(s) to that file, or set the ")
                        append("matching BITEY_* environment values.")
                    } else {
                        append("No such file: ${signingPropertiesFile.absolutePath}\n")
                        append("Copy android/keystore.properties.example to exactly ")
                        append("that path, or set the BITEY_* environment values.")
                    }
                }
            }
            val store = resolvedStoreFile
            check(store != null && store.isFile) {
                "The release keystore was not found at:\n" +
                    "  ${store?.absolutePath}\n" +
                    "storeFile in ${signingPropertiesFile.absolutePath} is resolved " +
                    "against ${rootProject.projectDir.absolutePath}. Point it at the " +
                    "keystore's real location, or give it an absolute path."
            }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.webkit:webkit:1.17.0")

    // Google Play is the only payment provider in the Android build. Product
    // metadata always comes from Play at purchase time; it is never bundled as
    // a price in the app.
    implementation("com.android.billingclient:billing:9.1.0")

    // Bundled scanning works as soon as the app is installed. It does not
    // download a barcode model or send camera frames to a service.
    val cameraXVersion = "1.6.2"
    implementation("androidx.camera:camera-camera2:$cameraXVersion")
    implementation("androidx.camera:camera-core:$cameraXVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraXVersion")
    implementation("androidx.camera:camera-view:$cameraXVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
}
