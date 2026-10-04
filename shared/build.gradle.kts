/*
 * Kotlin Multiplatform module that carries BitChord onto iOS.
 *
 * Code moves here from :app as each phase ports it: platform-neutral logic into
 * commonMain, iOS implementations of Android-only pieces (playback, WebView,
 * downloads, ...) into iosMain. The iOS app links the resulting static
 * framework, `Shared`, from iosApp/.
 */
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
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
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
    }
}
