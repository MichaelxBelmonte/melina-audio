pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        ivy {
            name = "SherpaOnnxReleases"
            url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
            patternLayout {
                artifact("v[revision]/[artifact]-[revision].[ext]")
            }
            metadataSources { artifact() }
        }
    }
}

rootProject.name = "Melina"
include(":audio-core")
include(":desktop")
val desktopOnly = providers.gradleProperty("melina.desktopOnly").isPresent ||
    providers.gradleProperty("michelina.desktopOnly").isPresent
if (!desktopOnly) {
    include(":app")
}
