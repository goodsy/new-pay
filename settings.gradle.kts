pluginManagement {
    // 공통 빌드 로직(paycore.kotlin-app / paycore.kotlin-library convention plugin).
    includeBuild("build-logic")
}

rootProject.name = "paycore"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

// 실행 단위별 모듈. 소유 DB 경계: docs/baseline/architecture/01-runtime-deployment-architecture.md
include(":apps:payment-api")
// FINANCIAL DB만 사용한다.
include(":apps:financial-batch")
// DB 없음. 화면은 Application/API 위임만 한다.
include(":apps:internal-admin")

// 공유 테스트 지원(testImplementation 전용 라이브러리). 실행 단위가 아니며 운영 runtime에 들어가지 않는다.
include(":libs:test-support")
