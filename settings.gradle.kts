pluginManagement {
    repositories {
        maven { url = uri("https://jitpack.io") }

        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }

        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)

    repositories {
        maven { url = uri("https://jitpack.io") }
        google()
        mavenCentral()

        // Required if you add local .aar files in libs folder
        flatDir { dirs("libs") }
    }
}

rootProject.name = "GlassesSDKSample"
include(":app")
