// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.baseroot.ok

import org.springframework.boot.autoconfigure.SpringBootApplication

@SpringBootApplication
class FixtureApplication

fun main(args: Array<String>) {
    println(args.joinToString())
}
