package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

/**
 * Kotest가 고정 이름(io.kotest.provided.ProjectConfig)으로 찾는 프로젝트 설정. 테스트 classpath의 이 라이브러리에서 로드된다
 * (app마다 복사하지 않는다 — 각 app의 TestInfrastructureSpec이 로드 위치를 확인한다).
 * Spring 생성자 주입을 쓰는 spec에 SpringExtension을 적용한다.
 *
 * TEST-ASSERT-001 재발 방지: Kotest 6.2.5는 data class끼리의 `shouldBe`가 equals 불일치 뒤 필드별 차이를 계산하고,
 * 기대값 안 중첩 `data object`(속성 0개)를 "차이 없음"으로 보아 다른 하위형을 통과시킨다(공허 통과 — 실측은
 * libs/test-support `KotestEqualityProbeSpec`, docs/repository/TEST-ASSERT-001-KOTEST-EQUALITY.md).
 * `kotest.assertions.show-data-class-diffs=false`이면 그 경로를 타지 않고 equals로만 판정한다(실패 메시지의 필드별 diff만 잃는다).
 * 시험 시작 시 한 번 설정하므로 모든 app 시험에 적용된다(각 app `TestInfrastructureSpec`이 이 값을 직접 생성 없이 확인한다).
 *
 * 주의(동시성): Kotest 동등 비교 probe(libs/test-support·internal-admin `KotestEqualityProbeSpec`)는 Kotest 기본 동작을 재려고 이 **JVM 전역
 * System property**를 잠시 `true`로 바꿨다 되돌린다. 지금은 spec·test를 순차 실행(Kotest 기본)하므로 안전하다. spec/test 병렬 실행
 * (`specExecutionMode`·`testExecutionMode`·Gradle `maxParallelForks`와 무관한 같은 JVM 안 병렬)을 켜려면 먼저 probe를 별도 JVM/task로
 * 분리해야 한다 — 그러지 않으면 probe가 바꾼 순간 다른 시험의 `shouldBe`가 공허 경로로 판정될 수 있다.
 */
class ProjectConfig : AbstractProjectConfig() {
    init {
        System.setProperty(SHOW_DATA_CLASS_DIFFS, "false")
    }

    override val extensions = listOf(SpringExtension())

    private companion object {
        const val SHOW_DATA_CLASS_DIFFS = "kotest.assertions.show-data-class-diffs"
    }
}
