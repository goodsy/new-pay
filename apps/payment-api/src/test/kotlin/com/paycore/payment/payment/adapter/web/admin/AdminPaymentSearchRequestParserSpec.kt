package com.paycore.payment.payment.adapter.web.admin

import com.paycore.payment.payment.application.provided.AdminPaymentCursor
import com.paycore.payment.payment.application.provided.AdminPaymentSearchCriteria
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * 관리자 거래 검색 본문 엄격 해석(TXN-OPS-001 §3.1·AC 2 — 순수 단위): 알려진 키만, 중복 key·뒤따르는 토큰·`null`·자료형 강제 변환 거부, 형식(시각·환경·ID·상태·limit 범위),
 * 모드 규칙(I-3 — paymentId 단독, 주문번호는 MID 필수, 그 밖 기간 필수), 기간 `[from, to)`·31일 상한(Demo)·역전 거부. 실패는 null(어느 필드인지 말하지 않는다).
 */
class AdminPaymentSearchRequestParserSpec : BehaviorSpec({

    val pay = "pay_01JC0000000000000000000001"
    val from = "2026-11-01T00:00:00.000Z"
    val to = "2026-11-02T00:00:00.000Z"
    fun parse(json: String) = AdminPaymentSearchRequestParser.parse(json.toByteArray(Charsets.UTF_8))

    Given("well-formed bodies") {
        Then("a period search with every filter, a cursor and a limit is read exactly") {
            parse(
                """{"createdFrom":"$from","createdTo":"$to","environment":"LIVE","merchantAccountId":"mid_01:a.b-c","status":"AUTHORIZED",""" +
                    """"after":{"createdAt":"2026-11-01T12:00:00.123Z","paymentId":"$pay"},"limit":50}""",
            ) shouldBe AdminPaymentSearchCriteria(
                createdFrom = Instant.parse(from), createdTo = Instant.parse(to), environment = "LIVE", merchantAccountId = "mid_01:a.b-c", status = "AUTHORIZED",
                after = AdminPaymentCursor(Instant.parse("2026-11-01T12:00:00.123Z"), pay), limit = 50,
            )
        }
        Then("the exact paymentId lookup and the order lookup need no period; the limit defaults to 20; a 31-day period is accepted") {
            parse("""{"paymentId":"$pay"}""") shouldBe AdminPaymentSearchCriteria(paymentId = pay)
            parse("""{"merchantAccountId":"mid-1","merchantOrderId":"order:1_A.b-c"}""") shouldBe
                AdminPaymentSearchCriteria(merchantAccountId = "mid-1", merchantOrderId = "order:1_A.b-c")
            parse("""{"createdFrom":"$from","createdTo":"2026-12-02T00:00:00.000Z"}""")?.limit shouldBe 20
        }
    }

    Given("malformed bodies") {
        Then("each is refused (null) without saying which field") {
            listOf(
                "", "[]", "null", "{}", "{\"createdFrom\":\"$from\",\"createdTo\":\"$to\"} {}",
                // 기간: 없음·한쪽만·역전·같음·31일 초과·형식(ms 없음·offset·날짜만).
                """{"environment":"TEST"}""", """{"createdFrom":"$from"}""", """{"createdFrom":"$to","createdTo":"$from"}""", """{"createdFrom":"$from","createdTo":"$from"}""",
                """{"createdFrom":"$from","createdTo":"2026-12-02T00:00:00.001Z"}""", """{"createdFrom":"2026-11-01T00:00:00Z","createdTo":"$to"}""",
                """{"createdFrom":"2026-11-01T09:00:00.000+09:00","createdTo":"$to"}""", """{"createdFrom":"2026-11-01","createdTo":"$to"}""",
                // 모르는 키·중복 key·null·자료형.
                """{"createdFrom":"$from","createdTo":"$to","orderName":"x"}""", """{"createdFrom":"$from","createdFrom":"$from","createdTo":"$to"}""",
                """{"createdFrom":"$from","createdTo":"$to","status":null}""", """{"createdFrom":"$from","createdTo":"$to","limit":"5"}""",
                """{"createdFrom":"$from","createdTo":"$to","limit":5.0}""", """{"createdFrom":"$from","createdTo":"$to","environment":1}""",
                // 값 형식.
                """{"createdFrom":"$from","createdTo":"$to","environment":"test"}""", """{"createdFrom":"$from","createdTo":"$to","status":"CANCELLED"}""",
                """{"createdFrom":"$from","createdTo":"$to","limit":0}""", """{"createdFrom":"$from","createdTo":"$to","limit":51}""",
                """{"createdFrom":"$from","createdTo":"$to","merchantAccountId":"mid 1"}""", """{"createdFrom":"$from","createdTo":"$to","merchantAccountId":"${"m".repeat(65)}"}""",
                """{"paymentId":"pay_x"}""", """{"paymentId":"pay_01jc0000000000000000000001"}""",
                // 모드: paymentId와 다른 키, 주문번호에 MID 없음, 주문번호 형식.
                """{"paymentId":"$pay","environment":"TEST"}""", """{"paymentId":"$pay","createdFrom":"$from","createdTo":"$to"}""",
                """{"merchantOrderId":"order-1"}""", """{"merchantAccountId":"mid-1","merchantOrderId":"order 1"}""",
                // cursor: 키 누락·추가·형식.
                """{"createdFrom":"$from","createdTo":"$to","after":{"createdAt":"2026-11-01T12:00:00.123Z"}}""",
                """{"createdFrom":"$from","createdTo":"$to","after":{"createdAt":"2026-11-01T12:00:00.123Z","paymentId":"$pay","x":1}}""",
                """{"createdFrom":"$from","createdTo":"$to","after":{"createdAt":"2026-11-01T12:00:00.123Z","paymentId":"pay_x"}}""",
                """{"createdFrom":"$from","createdTo":"$to","after":"2026-11-01T12:00:00.123Z"}""",
            ).forEach { body -> withClue(body) { parse(body) shouldBe null } }
            AdminPaymentSearchRequestParser.parse(byteArrayOf(0xC3.toByte(), 0x28)) shouldBe null
        }
    }
})
