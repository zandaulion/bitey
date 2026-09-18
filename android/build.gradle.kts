plugins {
    // AGP 9.3 is the newest compatible with Gradle 9.5, which the committed
    // wrapper pins. Keep this pair in lockstep: changing either one without
    // the other is what breaks a fresh checkout.
    id("com.android.application") version "9.3.3" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
