pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
        // Sandbox builds: mirror fallbacks for when the shared egress IP
        // gets rate-limited (429) or a mirror gap occurs upstream.
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        maven("https://maven.aliyun.com/repository/gradle-plugin")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
    }
}

rootProject.name = "turnypt"

include(":app")
include(":keyboard")

include(":core:common")
include(":core:crypto")
include(":core:messages")
include(":core:keys")
include(":core:sessions")

include(":data:database")
include(":data:repository")
include(":data:preferences")

include(":security:storage")
include(":security:clipboard")
include(":security:policies")

include(":plugins:api")
include(":plugins:registry")
