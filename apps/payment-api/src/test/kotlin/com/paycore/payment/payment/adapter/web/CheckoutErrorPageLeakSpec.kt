package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.domain.fail
import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.ScriptedCardAuthenticationPort
import com.paycore.payment.payment.domain.ProviderOutcome
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 결제창 오류·결과 화면 누출 바이트 검사(AUTHN-001 §12.5, 검토 #3 n-1): 400(토큰)·401(쿠키 없음)·403(CSRF)·409(결제 불가)·410(인증 시간 경과)·422(카드사 불허)·503(확인 중)·
 * 200(인증 실패 선택) 화면의 응답 바이트에 예외·stack·SQL·Snapshot 버전·기관 코드·내부 분류·비밀 원문이 없다. 확인 중(503) 화면의 재시도 링크는 설계상 같은 복귀 URL(그 결과
 * 참조·state)을 담는다(§6.3) — 그 두 값만 그 화면에서 허용한다.
 */
@PaymentApiTest
class CheckoutErrorPageLeakSpec(
    env: SpringEnvironment,
    private val db: DbAccess,
    private val checkoutConnector: CheckoutConnector,
    private val institution: ScriptedCardAuthenticationPort,
    payments: JpaPaymentRepository,
    controls: JpaOrderPaymentControlRepository,
    transactionManager: PlatformTransactionManager,
) : BehaviorSpec({

    val api = ApiClient(env.getRequiredProperty("local.server.port").toInt())
    val s = ApiSpecSupport(db, payments, controls, transactionManager)

    Given("every checkout error and result page") {
        Then("the response bytes carry only fixed text and server-built links") {
            val b = CheckoutBrowser(api, CheckoutClient(checkoutConnector.localPort), s, db, institution)
            val pages = linkedMapOf<String, Pair<CheckoutClient.Response, Set<String>>>()
            val o = b.opened()
            pages["400 token"] = b.web.get("/checkout?token=" + b.secrets.first()) to emptySet()
            pages["401 cookie"] = b.web.get("/checkout/s/${o.handle}") to emptySet()
            pages["403 csrf"] = b.web.post("/checkout/s/${o.handle}/cancel", mapOf("_csrf" to "x".repeat(43)), o.cookie) to emptySet()
            pages["422 card"] = b.start(o, cardCompany = "SIMISS-Z") to emptySet()
            b.start(o)
            pages["200 failed"] = b.web.get(b.returnPath(o, ProviderOutcome.FAILED), o.cookie) to emptySet()
            pages["410 late"] = b.web.get(latePath(b, o), o.cookie) to emptySet()
            b.start(o)
            val unverifiable = b.returnPath(o, outcome = null)
            pages["503 checking"] = b.web.get(unverifiable, o.cookie) to setOf(
                unverifiable.substringAfter("resultRef=").substringBefore('&'), unverifiable.substringAfter("state="),
            )
            val other = b.opened()
            s.change(other.cred, other.orderId, other.paymentId) { p, _ -> p.startAuthorization(); p.fail(com.paycore.payment.payment.domain.TerminationEvidence.PRE_TRANSMISSION_FAILURE_CONFIRMED) }
            pages["409 not available"] = b.web.get("/checkout/s/${other.handle}", other.cookie) to emptySet()
            pages.forEach { (name, entry) ->
                val (r, allowed) = entry
                withClue(name) {
                    val bytes = r.body
                    FORBIDDEN.forEach { marker -> withClue(marker) { bytes.contains(marker) shouldBe false } }
                    (b.secrets - allowed).forEach { secret -> withClue(secret.take(6)) { bytes.contains(secret) shouldBe false } }
                }
            }
            pages.map { it.value.first.status }.toSet() shouldBe setOf(400, 401, 403, 422, 200, 410, 503, 409)
        }
    }
}) {
    companion object {
        /** 화면에 나오면 안 되는 표식(예외·SQL·내부 분류·Snapshot·기관 코드). */
        val FORBIDDEN = listOf(
            "Exception", "exception", "at com.", "at org.", "java.", "SQL", "sqlState", "SELECT ", "es1:", "SIMAUTH", "SIMACQ", "sim-ev-", "sat_",
            "TIMEOUT", "SERVER_ERROR", "SNAPSHOT", "STATE_MISMATCH", "BINDING_MISMATCH", "EVIDENCE_CONFLICT", "PersistenceFailure",
        )

        /** 이미 FAILED가 된 세션의 늦은 복귀(P1 → 410): 같은 state(직전 returnPath가 마지막에 넣은 값)·새 결과 참조. */
        fun latePath(b: CheckoutBrowser, o: CheckoutBrowser.Opened): String {
            val last = b.secrets.last()
            return "/checkout/s/${o.handle}/authentication/return?resultRef=${ScriptedCardAuthenticationPort.newResultRef()}&state=$last"
        }
    }
}
