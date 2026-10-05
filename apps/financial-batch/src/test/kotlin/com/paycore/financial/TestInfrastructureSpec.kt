package com.paycore.financial

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.provided.ProjectConfig

/**
 * 공유 테스트 기반 배선 확인. Kotest ProjectConfig(SpringExtension)가 이 app 사본이 아니라 libs/test-support에서 로드되고,
 * Mockito가 테스트 classpath에 없다(MockK 사용). SpringExtension이 실제로 적용되는 증거는 생성자 주입 spec
 * (MainMigrationLocationSpec 등)이 동작하는 것이다.
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
})
