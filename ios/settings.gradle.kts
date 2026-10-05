// Standalone Gradle build for the iOS port, kept apart from the Android build
// at the repo root so it can track the Kotlin version InnerTubeX's iOS klibs
// are compiled with (they need a compiler at least that new).
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io") {
            content { includeGroupAndSubgroups("com.github.MetrolistGroup") }
        }
    }
}

rootProject.name = "BitChordIOS"
include(":shared")
