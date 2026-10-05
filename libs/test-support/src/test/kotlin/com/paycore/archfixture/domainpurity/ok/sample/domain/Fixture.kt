// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.ok.sample.domain

data class Money(val amount: Long) {
    init {
        require(amount >= 0) { "amount must not be negative" }
    }
}

class Order(val id: String, val total: Money)
