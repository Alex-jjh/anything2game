pluginManagement {
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        maven("https://repo1.maven.org/maven2/")
    }
}
rootProject.name = "anything2game"

include(":contract", ":android:engine")
// A2G_SERVER_ONLY=1 builds the backend (and its Docker image) without an Android SDK.
if (System.getenv("A2G_SERVER_ONLY") != "1") {
    include(":android:app")
}
include(":server")
