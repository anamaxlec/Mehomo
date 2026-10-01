pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google {
      content {
        includeGroupByRegex("androidx.*")
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
      }
    }
    mavenCentral()
  }
}

rootProject.name = "Memoh"

include(":app")
include(":core:model")
include(":core:network")
include(":core:data")
include(":core:designsystem")
include(":core:markdown")
include(":feature:login")
include(":feature:bots")
include(":feature:sessions")
include(":feature:chat")
include(":feature:settings")
