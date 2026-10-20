package com.paycore.payment.payment.application

import com.paycore.payment.payment.application.provided.PaymentStatusResult
import com.paycore.payment.payment.application.provided.PaymentViewStatus
import com.paycore.payment.payment.application.required.PaymentStatusReader
import com.paycore.payment.payment.application.required.PaymentStatusRow
import com.paycore.payment.payment.domain.Environment
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.PaymentId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/**
 * 조회 서비스(CP-QUERY-001 §4.2·§4.3): 범위(environment·MID)를 그대로 Reader에 넘기고, 없음·범위 밖·형식 밖을 같은 NotFound로 돌려준다. 순수 단위 시험(Reader 대역 — 범위 강제의 실제 SQL은
 * S2 실 MySQL 시험).
 */
class PaymentStatusQueryServiceSpec : BehaviorSpec({

    class RecordingReader(private val rows: Map<Triple<Environment, MerchantAccountId, PaymentId>, PaymentStatusRow>) : PaymentStatusReader {
        val calls = mutableListOf<Triple<Environment, MerchantAccountId, PaymentId>>()
        override fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow? {
            calls += Triple(environment, merchantAccountId, paymentId)
            return rows[Triple(environment, merchantAccountId, paymentId)]
        }
    }

    val mid = MerchantAccountId("mid-demo-a")
    val row = PaymentStatusRowFixture.authorized()
    val reader = RecordingReader(mapOf(Triple(Environment.TEST, mid, row.paymentId) to row))
    val service = PaymentStatusQueryService(reader)

    Given("the owner's environment and MID") {
        Then("the view is found and the scope is passed to the reader unchanged") {
            val r = service.find(Environment.TEST, mid, row.paymentId.value).shouldBeInstanceOf<PaymentStatusResult.Found>()
            r.view.status shouldBe PaymentViewStatus.AUTHORIZED
            reader.calls.last() shouldBe Triple(Environment.TEST, mid, row.paymentId)
        }
    }

    Given("another MID, another environment, an unknown id") {
        Then("each is the same NotFound") {
            service.find(Environment.TEST, MerchantAccountId("mid-demo-b"), row.paymentId.value) shouldBe PaymentStatusResult.NotFound
            service.find(Environment.LIVE, mid, row.paymentId.value) shouldBe PaymentStatusResult.NotFound
            service.find(Environment.TEST, mid, "pay_01UNKNOWN") shouldBe PaymentStatusResult.NotFound
        }
    }

    Given("a paymentId that is not a valid identifier") {
        Then("NotFound without asking the reader") {
            val before = reader.calls.size
            service.find(Environment.TEST, mid, "") shouldBe PaymentStatusResult.NotFound
            service.find(Environment.TEST, mid, "p".repeat(65)) shouldBe PaymentStatusResult.NotFound
            // 검토 #1 n-1: 웹 경로 판정과 같은 술어(printable ASCII 1..64) — 공백·비ASCII도 Reader를 부르지 않는다.
            service.find(Environment.TEST, mid, "pay 01") shouldBe PaymentStatusResult.NotFound
            service.find(Environment.TEST, mid, "pay_한글") shouldBe PaymentStatusResult.NotFound
            reader.calls.size shouldBe before
        }
    }

    // 검토 #1 m-2: Reader의 읽기 전용 TX 시작·종료 실패(연결 획득 실패 등)도 503 분류(PersistenceFailure.Unavailable) — 명령 서비스와 같은 매핑.
    Given("the reader's transaction cannot be begun or ends unexpectedly") {
        // 전체 check 수정(webAdapterUsesOnlyApplication): 503은 web이 application.required(PersistenceFailure)를 모르도록 provided 결과 `Unavailable(FailureKind)`로 돌려준다(cancel·confirm의 provided 실패 타입과 같은 방식).
        Then("each Spring transaction failure is the provided Unavailable(DB_UNAVAILABLE) result") {
            listOf<RuntimeException>(
                org.springframework.transaction.CannotCreateTransactionException("injected: no connection"),
                org.springframework.transaction.TransactionSystemException("injected: commit failed"),
                org.springframework.transaction.UnexpectedRollbackException("injected: rolled back"),
            ).forEach { failure ->
                val failing = PaymentStatusQueryService(object : PaymentStatusReader {
                    override fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow? = throw failure
                })
                failing.find(Environment.TEST, mid, row.paymentId.value) shouldBe
                    PaymentStatusResult.Unavailable(com.paycore.payment.payment.application.provided.FailureKind.DB_UNAVAILABLE)
            }
        }
        Then("a store failure keeps its kind (e.g. lock timeout) in the Unavailable result") {
            val failing = PaymentStatusQueryService(object : PaymentStatusReader {
                override fun find(environment: Environment, merchantAccountId: MerchantAccountId, paymentId: PaymentId): PaymentStatusRow? =
                    throw com.paycore.payment.payment.application.required.PersistenceFailure.Unavailable(RuntimeException("injected"))
            })
            failing.find(Environment.TEST, mid, row.paymentId.value) shouldBe
                PaymentStatusResult.Unavailable(com.paycore.payment.payment.application.provided.FailureKind.DB_UNAVAILABLE)
        }
    }
})
