pluginManagement {
    repositories {
        // CN mirrors first so builds work without a proxy; official repos stay as fallback.
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenLocal()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenLocal()
        mavenCentral()
    }
}

rootProject.name = "ghostloc-offline"

include(":vector-api-stubs")
include(":app")
include(":probe-app")
include(":demo-app")
