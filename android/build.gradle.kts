plugins {
    // AGP 9.4 requires Gradle 9.6, which the committed wrapper pins. Keep this
    // pair in lockstep: changing either one without the other is what breaks a
    // fresh checkout. AGP states its minimum outright, so a mismatch fails the
    // build with the version it wanted rather than anything subtler.
    id("com.android.application") version "9.4.1" apply false
    id("com.google.devtools.ksp") version "2.3.10" apply false
}
