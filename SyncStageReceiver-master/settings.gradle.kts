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
        // Add JitPack repository to find the TrueTime library.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "SyncStageReceiver"
include(":app")
