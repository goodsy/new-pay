pluginManagement {
    // 공통 빌드 로직(paycore.kotlin-app / paycore.kotlin-library convention plugin).
    includeBuild("build-logic")
}

rootProject.name = "paycore"

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        // 프론트 빌드용 Node 배포판(ADMIN-BOOT-001 F-8). node-gradle plugin이 project 저장소를 추가하면 FAIL_ON_PROJECT_REPOS에 걸리므로
        // plugin의 distBaseUrl을 비우고 여기서만 선언한다. 이 저장소는 org.nodejs:node만, org.nodejs:node는 이 저장소에서만 해석한다.
        // 압축 SHA-256은 paycore.frontend의 frontendNodeVerify가 catalog 값과 대조한다(F-9 — Gradle dependency verification 미사용).
        exclusiveContent {
            forRepository {
                ivy {
                    name = "nodejs-dist"
                    setUrl("https://nodejs.org/dist/")
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                }
            }
            filter { includeModule("org.nodejs", "node") }
        }
    }
}

// 실행 단위별 모듈. 소유 DB 경계: docs/baseline/architecture/01-runtime-deployment-architecture.md
// MASTER DB만 사용한다(D-CPC-006, MASTER-BOOT-001).
include(":apps:master-api")
// PAYMENT DB만 사용한다.
include(":apps:payment-api")
// FINANCIAL DB만 사용한다.
include(":apps:financial-batch")
// DB 없음. 화면은 Application/API 위임만 한다.
include(":apps:internal-admin")
// 가상 인증기관(외부 기관 대역, D-AUTHN-004). PayCore DB를 쓰지 않고 Simulator 전용 MySQL(paycore_simulator)만 쓴다. PayCore 앱과 Gradle project
// 의존이 없다(harness test_simulator_isolation). TEST 전용 — REAL 기관 연동 없음.
include(":apps:institution-simulator")

// 공유 테스트 지원(testImplementation 전용 라이브러리). 실행 단위가 아니며 운영 runtime에 들어가지 않는다.
include(":libs:test-support")
