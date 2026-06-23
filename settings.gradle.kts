pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

fun loadProperty(key: String): String? {
    val props = java.util.Properties()
    file("gradle.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    file("local.properties").takeIf { it.isFile }?.inputStream()?.use { props.load(it) }
    return props.getProperty(key)
}

val useCompositeSdk = loadProperty("useCompositeSdk")?.toBoolean() ?: false
val useMavenSdk = loadProperty("useMavenSdk")?.toBoolean() ?: false

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        if (useMavenSdk && !useCompositeSdk) {
            maven {
                url = uri("https://maven.pkg.github.com/posrouter/sdk-android")
                credentials {
                    username = loadProperty("gpr.user").orEmpty()
                    password = loadProperty("gpr.key").orEmpty()
                }
            }
        }
    }
}

if (useCompositeSdk) {
    includeBuild("../sdk-android") {
        dependencySubstitution {
            substitute(module("com.posrouter:posrouter")).using(project(":posrouter"))
        }
    }
}

rootProject.name = "demo-android"
include(":app")
