import java.util.Properties

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// The Meta Wearables Device Access Toolkit is served from GitHub Packages, which
// always requires authentication — even for these public artifacts. Provide a
// GitHub personal access token (classic) with the read:packages scope as either
// the GITHUB_TOKEN environment variable (used by CI) or a github_token entry in
// local.properties (used on your machine, and never committed to git).
val githubToken: String? = run {
    val local = rootDir.resolve("local.properties")
    val fromFile = if (local.exists()) {
        Properties().apply { local.inputStream().use { load(it) } }.getProperty("github_token")
    } else null
    fromFile ?: System.getenv("GITHUB_TOKEN")
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/facebook/meta-wearables-dat-android")
            credentials {
                username = "" // not used by GitHub Packages, but must be present
                password = githubToken
            }
        }
    }
}

rootProject.name = "FaceID"
include(":app")
