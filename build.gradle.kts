// AGP 8.2.2 / Kotlin 1.9.24 on Gradle 8.11.1 — the combination every Android
// project on this machine is already building with, and the Gradle
// distribution that is already in ~/.gradle/wrapper/dists. Moving any one of
// the three means downloading and proving the other two again.
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
