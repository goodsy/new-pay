package com.paycore.payment.payment.application

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMe
import com.paycore.payment.payment.adapter.persistence.PersistenceTestData
import com.paycore.payment.payment.application.provided.CreatePaymentCommand
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OrderIntent

/**
 * 대량 CreatePayment 입력의 프로젝트 Fixture(Test Fixture Policy 5·6·8). Fixture Monkey는 이 객체 안에만 있고 시험 본문은 업무 이름
 * ([validNewOrders])만 부른다. Repository/application 통합 시험용 대량 데이터이며 핵심 Domain Rule 시험은 명시 Fixture를 쓴다.
 *
 * 정상 Fixture는 Domain Invariant를 만족한다(정책 2·4): Fixture Monkey가 만든 원시 seed를 그대로 쓰지 않고, 금액(1 이상)·통화(초기 검증
 * 통화 KRW/USD — D-CP-002 Demo, 운영 허용 목록 아님)·주문명(제어문자·짝 없는 서로게이트 제거, 100 code point 이하)을 **공식 생성 경로**
 * (`Money`·`OrderIntent`·`CreatePaymentCommand` 생성자)로 만든다. seed를 고정해 재현 가능하다(정책 8).
 */
object RandomCreatePaymentFixture {

    /** Fixture Monkey가 채우는 원시 seed(업무 의미 없음). */
    data class RawOrderSeed(val amountSeed: Long, val useUsd: Boolean, val nameSeed: String, val nameAbsent: Boolean)

    fun validNewOrders(count: Int, seed: Long): List<CreatePaymentCommand> {
        val monkey = FixtureMonkey.builder().plugin(KotlinPlugin()).seed(seed).build()
        return monkey.giveMe<RawOrderSeed>(count).map { raw ->
            val currency = if (raw.useUsd) CurrencyCode("USD") else CurrencyCode("KRW")
            val amount = Math.floorMod(raw.amountSeed, MAX_DEMO_AMOUNT) + 1
            CreatePaymentFixture.command(
                order = PersistenceTestData.newOrder(),
                intent = OrderIntent(PersistenceTestData.demoSite, Money(amount, currency)),
                orderName = if (raw.nameAbsent) null else displayName(raw.nameSeed),
            )
        }
    }

    /** 표시용 주문명: 출력 가능한 code point만, 최대 100개. 남는 것이 없으면 Demo 고정 문구. */
    private fun displayName(seed: String): String {
        val cleaned = buildString {
            var count = 0
            val points = seed.codePoints().iterator()
            while (points.hasNext() && count < MAX_ORDER_NAME) {
                val cp = points.nextInt()
                // codePoints()는 짝 없는 서로게이트를 D800..DFFF 값으로 그대로 내준다.
                if (Character.isISOControl(cp) || cp in 0xD800..0xDFFF || !Character.isDefined(cp) || cp in FORBIDDEN_FORMAT) continue
                appendCodePoint(cp)
                count++
            }
        }
        return cleaned.ifEmpty { "Demo order" }
    }

    /** Demo 금액 범위(운영 한도 아님, D-CPC-005: 한도는 기준정보). */
    private const val MAX_DEMO_AMOUNT = 1_000_000_000L
    private const val MAX_ORDER_NAME = 100

    /** OrderNameRule(CP-API-001 §4.4)이 거절하는 줄 구분자·양방향 제어 문자. */
    private val FORBIDDEN_FORMAT = setOf(0x2028, 0x2029) + (0x202A..0x202E) + (0x2066..0x2069)
}
