// PayCore 공통 빌드 로직(included build). 루트 settings.gradle.kts의 pluginManagement.includeBuild로 포함된다.
// plugin 버전은 저장소 하나의 version catalog(gradle/libs.versions.toml)만 기준으로 한다(중복 버전 literal 금지).
dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
