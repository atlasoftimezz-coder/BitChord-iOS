/*
 * Kotlin Multiplatform module that carries BitChord onto iOS.
 *
 * Code moves here from the Android app (../../app) as each phase ports it:
 * platform-neutral logic into commonMain under the same packages, iOS
 * implementations of Android-only pieces into iosMain. Playback itself is
 * AVFoundation, implemented in Swift (iosApp/) behind `AudioEngine`.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.compose")
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        all {
            languageSettings.optIn("kotlinx.cinterop.ExperimentalForeignApi")
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)

            // Same InnerTubeX the Android app resolves streams with; its iOS
            // klibs carry the QuickJS cipher solver too.
            implementation("com.github.MetrolistGroup.innertubex:innertubex:v0.7.4")

            implementation("io.ktor:ktor-client-core:3.5.2")
            implementation("io.ktor:ktor-client-content-negotiation:3.5.2")
            implementation("io.ktor:ktor-serialization-kotlinx-json:3.5.2")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

            implementation("io.coil-kt.coil3:coil-compose:3.6.3")
            implementation("io.coil-kt.coil3:coil-network-ktor3:3.6.3")
        }
        iosMain.dependencies {
            implementation("io.ktor:ktor-client-darwin:3.5.2")
        }
    }
}
