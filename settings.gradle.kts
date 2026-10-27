pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (avoids 429 rate limits on repo.maven.apache.org).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (avoids 429 rate limits on repo.maven.apache.org).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "ColabMcpBridge"
include(":core", ":app")
