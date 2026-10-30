pluginManagement {
    val user = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val password = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    repositories {
        if (user != null && password != null) {
            maven {
                name = "takArtifactory"
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val user = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    val password = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
        .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
    logger.lifecycle("MOPP Artifactory routing: userSet=${user != null}, passwordSet=${password != null}, enabled=${user != null && password != null}")
    repositories {
        if (user != null && password != null) {
            maven {
                name = "takArtifactory"
                url = uri("https://artifacts.tak.gov/artifactory/maven-release")
                credentials {
                    username = user
                    this.password = password
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-plugin-mopp"
include(":app")
