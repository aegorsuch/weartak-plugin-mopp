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
    println("takArtifactory for plugins: ${if (user != null && password != null) "enabled" else "disabled (no credentials in environment)"}")
}

val takArtifactoryUrl = "https://artifacts.tak.gov/artifactory/maven-release"
val takUser = listOf("ARTIFACTORY_CONSUME_USER", "ARTIFACTORY_USER", "TAK_USERNAME")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
val takPass = listOf("ARTIFACTORY_CONSUME_PASSWORD", "ARTIFACTORY_PASSWORD", "TAK_PASSWORD")
    .firstNotNullOfOrNull { System.getenv(it)?.takeIf(String::isNotBlank) }
if (takUser != null && takPass != null) {
    gradle.extra["takArtifactoryUrl"] = takArtifactoryUrl
    gradle.extra["takArtifactoryUser"] = takUser
    gradle.extra["takArtifactoryPassword"] = takPass
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    logger.lifecycle("MOPP Artifactory routing: userSet=${takUser != null}, passwordSet=${takPass != null}, enabled=${takUser != null && takPass != null}")
    repositories {
        if (takUser != null && takPass != null) {
            maven {
                name = "takArtifactory"
                url = uri(takArtifactoryUrl)
                credentials {
                    username = takUser
                    password = takPass
                }
            }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "weartak-plugin-mopp"
include(":app")
