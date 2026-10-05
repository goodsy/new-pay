// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.ok.sample.domain

import com.paycore.archfixture.domainpurity.ok.sample.domain.shared.Rate
import java.time.Instant
import java.util.UUID

/** kotlin/java 표준(java.time, java.math, java.util), 같은 모듈 domain만 쓰는 준수 예. */
enum class OrderStatus { OPEN, CLOSED }

sealed interface OrderEvent {
    data class Opened(val at: Instant) : OrderEvent
    data object Closed : OrderEvent
}

class Pricing(val rate: Rate) {
    fun describe(items: List<Money>): String = items.filter { it.amount > 0 }.joinToString { "${it.amount}@${rate.value}" }
    fun newId(): UUID = UUID.randomUUID()
}
