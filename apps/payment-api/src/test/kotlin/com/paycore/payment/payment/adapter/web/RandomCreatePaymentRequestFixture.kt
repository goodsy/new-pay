package com.paycore.payment.payment.adapter.web

import com.navercorp.fixturemonkey.FixtureMonkey
import com.navercorp.fixturemonkey.kotlin.KotlinPlugin
import com.navercorp.fixturemonkey.kotlin.giveMe
import com.paycore.payment.payment.application.provided.OrderNameRule

/**
 * 대량 API 요청 본문의 프로젝트 Fixture(Test Fixture Policy 5·6·8, CP-API-001 명세 §12). Fixture Monkey는 이 객체 안에만 있고 시험 본문은 업무 이름
 * ([validRequests])만 부른다. Fixture Monkey가 만든 원시 seed를 그대로 쓰지 않고 공식 규칙(§4.3·§4.4)을 만족하는 정상 본문으로 바꾼다:
 * 금액 1..Demo 상한, 통화 KRW/USD(D-CP-002 Demo — 운영 허용 목록 아님), 주문명은 [OrderNameRule]을 만족하는 code point만(없으면 생략),
 * 주문 ID는 D-CPC-002 문자 집합. seed 고정으로 재현 가능. 핵심 규칙 시험(오류 매핑·경계)은 명시 Fixture([CreatePaymentRequests])를 쓴다.
 */
object RandomCreatePaymentRequestFixture {

    /** Fixture Monkey가 채우는 원시 seed(업무 의미 없음). */
    data class RawRequestSeed(val amountSeed: Long, val useUsd: Boolean, val nameSeed: String, val nameAbsent: Boolean, val orderSeed: Long)

    data class RandomRequest(val orderId: String, val body: String)

    fun validRequests(mid: String, count: Int, seed: Long): List<RandomRequest> {
        val monkey = FixtureMonkey.builder().plugin(KotlinPlugin()).seed(seed).build()
        return monkey.giveMe<RawRequestSeed>(count).mapIndexed { i, raw ->
            val orderId = "rnd-$i-" + java.lang.Long.toUnsignedString(raw.orderSeed, 36)
            val name = if (raw.nameAbsent) null else displayName(raw.nameSeed)
            RandomRequest(
                orderId,
                CreatePaymentRequests.valid(
                    mid = mid,
                    orderId = orderId,
                    amount = Math.floorMod(raw.amountSeed, MAX_DEMO_AMOUNT) + 1,
                    currency = if (raw.useUsd) "USD" else "KRW",
                    orderName = name,
                ),
            )
        }
    }

    private fun displayName(seed: String): String? {
        val cleaned = buildString {
            seed.codePoints().forEach { cp ->
                val candidate = String(Character.toChars(cp))
                if (Character.isDefined(cp) && OrderNameRule.isValid(candidate) && toString().codePointCount(0, length) < OrderNameRule.MAX_CODE_POINTS) {
                    append(candidate)
                }
            }
        }
        return cleaned.takeIf { OrderNameRule.isValid(it) }
    }

    /** Demo 금액 범위(운영 한도 아님, D-CPC-005). */
    private const val MAX_DEMO_AMOUNT = 1_000_000_000L
}
