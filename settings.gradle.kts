rootProject.name = "paycore"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// 실행 단위별 모듈. 소유 DB 경계: docs/baseline/architecture/01-runtime-deployment-architecture.md
include(":apps:payment-api")
