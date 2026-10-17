package com.paycore.payment.payment.adapter.web

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.JpaOrderPaymentControlRepository
import com.paycore.payment.payment.adapter.persistence.JpaPaymentRepository
import com.paycore.payment.payment.application.ScriptedCardAuthenticationPort
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.core.env.Environment as SpringEnvironment
import org.springframework.transaction.PlatformTransactionManager

/**
 * 한 브라우저의 동시 결제창 2개(AUTHN-001 판정 m-1·§12.2, 검토 #3 n-1): 두 결제창의 handle 쿠키를 **매 요청에 함께** 보내도(같은 브라우저) 각 결제창은 자기 handle 쿠키로만
 * 인증되고, 번갈아 진행한 인증 시작·복귀가 각자의 Payment로 끝난다. 한쪽 취소는 다른 쪽에 영향이 없다.
 */
@PaymentApiTest
class CheckoutTwoWindowsSpec(
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

    Given("two checkouts open in the same browser (both cookies sent on every request)") {
        Then("interleaved starts and returns complete each payment with its own success redirect; cancelling one leaves the other usable") {
            val b = CheckoutBrowser(api, CheckoutClient(checkoutConnector.localPort), s, db, institution)
            val one = b.opened(orderName = "first")
            val two = b.opened(orderName = "second")
            val both = "${one.cookie}; ${two.cookie}"

            b.start(one, cookie = both).status shouldBe 303
            b.start(two, cookie = both).status shouldBe 303
            val backTwo = b.web.get(b.returnPath(two), both)
            val backOne = b.web.get(b.returnPath(one), both)
            backOne.status shouldBe 303
            backTwo.status shouldBe 303
            backOne.header("Location")!! shouldStartWith "https://shop.example.test/ok?paymentId=${one.paymentId}&authenticationRef=aref_"
            backTwo.header("Location")!! shouldStartWith "https://shop.example.test/ok?paymentId=${two.paymentId}&authenticationRef=aref_"

            val three = b.opened(orderName = "third")
            val four = b.opened(orderName = "fourth")
            val pair = "${three.cookie}; ${four.cookie}"
            b.cancel(three, cookie = pair).status shouldBe 303
            b.web.get("/checkout/s/${three.handle}", pair).status shouldBe 401
            b.web.get("/checkout/s/${four.handle}", pair).status shouldBe 200
        }
    }
})
