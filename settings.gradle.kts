pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

includeBuild("70mai_file_protocaol") {
    dependencySubstitution {
        substitute(module("com.github.trunone.mai70protocol:core")).using(project(":core"))
        substitute(module("com.github.trunone.mai70protocol:android")).using(project(":android"))
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "70maiDashcamSAF"
include(":app")
