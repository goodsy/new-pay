package com.paycore.admin

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.provided.ProjectConfig

/**
 * 공유 테스트 기반 배선 확인. Kotest ProjectConfig(SpringExtension)가 이 app 사본이 아니라 libs/test-support에서 로드되고,
 * Mockito가 테스트 classpath에 없다(MockK 사용). SpringExtension이 실제로 적용되는 증거는 생성자 주입 spec
 * (SpaCsrfSpec·AdminDatabaseAccountSpec 등)이 동작하는 것이다.
 */
class TestInfrastructureSpec : FunSpec({

    test("Kotest ProjectConfig comes from libs/test-support") {
        val location = ProjectConfig::class.java.protectionDomain.codeSource.location.toString()
        location shouldContain "libs/test-support"
        location shouldNotContain "/apps/"
    }

    test("Mockito is excluded from the test classpath") {
        shouldThrow<ClassNotFoundException> { Class.forName("org.mockito.Mockito") }
    }

    test("Testcontainers MySQL is on the test classpath (ADMIN DB tests) and stays off the runtime classpath") {
        // ADMIN-BOOT-001 §3.3 F-2(Coordinator 승인 기대값 변경 — D-ADMIN-005 ADMIN DB 소유): 이전 기대 "로드 실패"는 internal-admin에 DB가 없을 때의
        // 주장이었다. 이제 ADMIN DB 시험이 실제 MySQL 8.4.11 컨테이너를 쓰므로 시험 classpath에 있어야 하고, runtime classpath(배포물·SBOM)에
        // 없다는 쪽은 paycore.kotlin-app의 verifyRuntimeHasNoTestTools(org.testcontainers 금지)가 check에서 강제한다.
        Class.forName("org.testcontainers.mysql.MySQLContainer").name shouldBe "org.testcontainers.mysql.MySQLContainer"
    }

    test("TEST-ASSERT-001 guard: the loaded ProjectConfig makes shouldBe equals-only (show-data-class-diffs=false), without constructing it here") {
        System.getProperty("kotest.assertions.show-data-class-diffs") shouldBe "false"
    }
})
