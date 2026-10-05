// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.domainpurity.ok.sample.domain

/**
 * Kotlin 컴파일러가 만드는 보조 타입이 domain 허용 목록을 통과하는지 보이는 준수 예:
 * companion object(`Ledger$Companion`), 다른 파일 enum의 `when`(`Ledger$WhenMappings`), suspend 함수(kotlin.coroutines),
 * `by lazy`(kotlin.Lazy). PayCoreArchitectureRulesSpec이 이 보조 타입들이 실제로 import되는지도 확인한다.
 */
class Ledger(private val entries: List<Money>) {
    val total: Long by lazy { entries.sumOf { it.amount } }

    fun label(status: OrderStatus): String = when (status) {
        OrderStatus.OPEN -> "open"
        OrderStatus.CLOSED -> "closed"
    }

    suspend fun settle(next: suspend (Long) -> Long): Long = next(total) + SCALE

    companion object {
        const val SCALE = 0L

        fun empty(): Ledger = Ledger(emptyList())
    }
}
