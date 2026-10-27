plugins {
    // AGP 9.4 requires at least Gradle 9.6; the committed wrapper pins 9.8.2.
    // Keep this pair in lockstep: changing either one without the other is what
    // breaks a fresh checkout. AGP states its minimum outright, so a mismatch fails the
    // build with the version it wanted rather than anything subtler.
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.12" apply false
    // The watch app is Compose, whose compiler plugin must match the Kotlin
    // Gradle plugin. Naming KGP here makes AGP's built-in Kotlin use this
    // version rather than the 2.2.10 it depends on, so the two agree; the
    // pairing (with KSP 2.3.12 on AGP 9.4.1) is the one pocket-omaha builds.
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
}
