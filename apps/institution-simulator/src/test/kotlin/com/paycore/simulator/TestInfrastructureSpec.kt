package com.paycore.simulator

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.provided.ProjectConfig

/**
 * 공유 테스트 기반 배선 확인(TEST-ASSERT-001): Simulator 시험도 libs/test-support의 Kotest ProjectConfig를 로드하고,
 * 그 설정(`kotest.assertions.show-data-class-diffs=false` — data class `shouldBe`를 equals로만 판정)이 이 모듈 시험에 적용된다.
 * 여기서 ProjectConfig를 생성하지 않는다(엔진이 로드한 결과만 확인).
 */
class TestInfrastructureSpec : FunSpec({

    test("Kotest ProjectConfig comes from libs/test-support") {
        val location = ProjectConfig::class.java.protectionDomain.codeSource.location.toString()
        location shouldContain "libs/test-support"
        location shouldNotContain "/apps/"
    }

    test("TEST-ASSERT-001 guard: the loaded ProjectConfig makes shouldBe equals-only (show-data-class-diffs=false), without constructing it here") {
        System.getProperty("kotest.assertions.show-data-class-diffs") shouldBe "false"
    }
})
