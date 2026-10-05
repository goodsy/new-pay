// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooq.ok.sample.application

class FindOrders {
    fun describe(): String = "uses a query port, not jOOQ"
}
