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
    }
}

rootProject.name = "duocb-android"

// One module: the Compose app. The pure-Kotlin parts (clip-item fingerprints,
// the config document, the channel model) live under app/ too and are covered
// by its JVM unit tests (`./gradlew :app:testDebugUnitTest`).
include(":app")
