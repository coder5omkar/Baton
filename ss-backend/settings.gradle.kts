pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "ss-backend"

include(
    "platform:platform-kernel",
    "exchange:exchange-domain",
    "exchange:exchange-persistence",
    "exchange:exchange-api",
    "exchange:exchange-worker",
)
