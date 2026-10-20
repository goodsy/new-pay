package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.adapter.persistence.FaultInjector
import com.paycore.payment.payment.adapter.persistence.FaultPoint
import com.paycore.payment.payment.adapter.web.PaymentApiTest
import com.paycore.payment.payment.application.provided.CancellationViewStatus
import com.paycore.payment.payment.application.provided.ConfirmPaymentUseCase
import com.paycore.payment.payment.application.provided.OperatorCancelRejectionCode
import com.paycore.payment.payment.application.provided.OperatorCancelTargetQuery
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelResult
import com.paycore.payment.payment.application.provided.OperatorCorrectionCancelUseCase
import com.paycore.payment.payment.application.required.AuthenticationSessionRepository
import com.paycore.payment.payment.application.required.CheckoutSessionStore
import com.paycore.payment.payment.application.required.CheckoutTokenStore
import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import com.paycore.payment.payment.domain.Money
import com.paycore.payment.payment.domain.OperatorCancelPurpose
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.transaction.PlatformTransactionManager

/**
 * 운영자 보정 취소 Use Case(CANCEL-001 §5 — 진입점 없음, D-CANCEL1-001). 소유 서비스 재검사(요청자 ≠ 승인자·사유·근거·상한·승인 대상 지문 — D-ADMIN-007, D-CANCEL-005), approvalId 영구
 * 업무 키(같은 금액 = 기존 결과, 다른 금액·다른 Payment = 충돌 — CN-10·CC-12), hold 중 시작(D-CANCEL-001), 거절마다 같은 로컬 TX의 감사 행 하나(D-CANCEL1-006·008 — 개인정보·사유 문구
 * 없음). 기대값은 명세 §5·§19에서 직접 적는다.
 */
@PaymentApiTest
class OperatorCorrectionCancelSpec(
    private val operator: OperatorCorrectionCancelUseCase,
    private val targets: OperatorCancelTargetQuery,
    private val start: CancelStartService,
    private val admission: ScriptedAdmissionPort,
    private val faults: FaultInjector,
    confirm: ConfirmPaymentUseCase,
    payments: PaymentRepository,
    controls: OrderPaymentControlRepository,
    sessions: AuthenticationSessionRepository,
    tokens: CheckoutTokenStore,
    checkoutSessions: CheckoutSessionStore,
    transactionManager: PlatformTransactionManager,
    db: DbAccess,
) : BehaviorSpec({

    val confirmFixture = ConfirmFixture(payments, controls, sessions, tokens, checkoutSessions, transactionManager, db)
    val fixture = CancelFixture(confirmFixture, confirm, admission, controls, payments, transactionManager)

    afterEach { faults.reset() }

    fun rejections(approvalId: String) =
        fixture.confirmFixture.let { _ -> fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ?", approvalId) }

    fun accepted(r: OperatorCorrectionCancelResult) = r.shouldBeInstanceOf<OperatorCorrectionCancelResult.Accepted>()

    Given("an authorized payment and a valid two-person approval") {
        Then("the correction cancel starts with the audit columns (requester, approver, approvalId, reason, purpose, approved target fingerprint) and no rejection row") {
            val a = fixture.authorized()
            val c = a.operatorCommand()
            val r = accepted(operator.cancel(c))
            r.existing shouldBe false
            // S6부터 새 시작은 가맹점 취소와 같은 전송·반영을 탄다(stub VAN 기본 승인 → COMPLETED). S5에서는 전송이 없어 IN_PROGRESS였다(§18.8 바뀐 기대값).
            r.view.status shouldBe CancellationViewStatus.COMPLETED
            val id = r.view.cancellationId.value
            fixture.scalar(
                "SELECT CONCAT_WS('|', initiator_kind, requested_by, approved_by, approval_id, purpose_kind, purpose_ref, approved_target_fingerprint) FROM payment_cancellation WHERE cancellation_id = ?",
                id,
            ) shouldBe "OPERATOR|opr-requester-01|opr-approver-02|${c.approvalId.value}|INCIDENT_OR_RECON_CORRECTION|case-demo-0001|${c.approvedTargetFingerprint}"
            fixture.scalar("SELECT operator_reason FROM payment_cancellation WHERE cancellation_id = ?", id) shouldBe c.reason
            rejections(c.approvalId.value) shouldBe 0
            // 운영자 명령은 멱등 기록을 쓰지 않는다(CN-10 — approvalId가 업무 키).
            fixture.count("SELECT COUNT(*) FROM idempotency_request WHERE merchant_account_id = ? AND operation = 'CancelPayment'", a.mid.value) shouldBe 0
        }
        Then("it starts while the payment is on hold (D-CANCEL-001) — the merchant is ORDER_ON_HOLD") {
            val a = fixture.authorized()
            fixture.holdOn(a)
            start.start(a.command()).shouldBeInstanceOf<CancelStartOutcome.Rejected>()
            accepted(operator.cancel(a.operatorCommand())).existing shouldBe false
        }
        Then("the target query returns the current approval target material (currency, cancellable amount, balance revision)") {
            val a = fixture.authorized()
            val t = targets.find(a.paymentId.value)!!
            t.cancellable shouldBe true
            t.cancellableMinor shouldBe 10_000
            t.currency shouldBe a.money.currency
            t.balanceRevision shouldBe 0
            targets.find("pay_doesnotexist0000000000") shouldBe null
        }
    }

    Given("approvalId as a permanent business key (CC-12)") {
        Then("the same approval and amount returns the existing cancellation; another amount or another payment is APPROVAL_CONFLICT (audited)") {
            val a = fixture.authorized()
            val c = a.operatorCommand()
            val first = accepted(operator.cancel(c))
            val again = accepted(operator.cancel(c))
            again.existing shouldBe true
            again.view.cancellationId shouldBe first.view.cancellationId
            operator.cancel(a.operatorCommand(approvalId = c.approvalId.value, money = Money(5_000, a.money.currency))).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>()
                .code shouldBe OperatorCancelRejectionCode.APPROVAL_CONFLICT
            val b = fixture.authorized()
            operator.cancel(b.operatorCommand(approvalId = c.approvalId.value)).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>().code shouldBe
                OperatorCancelRejectionCode.APPROVAL_CONFLICT
            rejections(c.approvalId.value) shouldBe 2
            fixture.count("SELECT COUNT(*) FROM payment_cancellation WHERE approval_id = ?", c.approvalId.value) shouldBe 1
        }
    }

    Given("owner re-checks (D-ADMIN-007) — every rejection is one audit row in the same TX, no cancellation row") {
        Then("same person, missing or over-long reason or basis, and a stale target are rejected with their codes") {
            val a = fixture.authorized()
            mapOf(
                OperatorCancelRejectionCode.OPERATOR_SAME_PERSON to a.operatorCommand(requestedBy = "opr-same-0001", approvedBy = "opr-same-0001"),
                OperatorCancelRejectionCode.OPERATOR_REASON_MISSING to a.operatorCommand(reason = " "),
                OperatorCancelRejectionCode.OPERATOR_REASON_INVALID to a.operatorCommand(reason = "r".repeat(501)),
                OperatorCancelRejectionCode.OPERATOR_BASIS_MISSING to a.operatorCommand(purpose = OperatorCancelPurpose.IncidentOrReconCorrection(" ")),
                OperatorCancelRejectionCode.OPERATOR_BASIS_INVALID to a.operatorCommand(purpose = OperatorCancelPurpose.MerchantTerminatedObligation("b".repeat(201))),
                OperatorCancelRejectionCode.OPERATOR_TARGET_STALE to a.operatorCommand(fingerprintRevision = 1),
                OperatorCancelRejectionCode.OPERATOR_TARGET_STALE to a.operatorCommand(fingerprintMoney = Money(9_999, a.money.currency)),
            ).forEach { (code, command) ->
                withClue(code) {
                    operator.cancel(command).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>().code shouldBe code
                    fixture.count(
                        "SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND payment_id = ? AND requested_by = ? AND approved_by = ? AND rejection_reason = ?",
                        command.approvalId.value, a.paymentId.value, command.requestedBy.value, command.approvedBy.value, code.name,
                    ) shouldBe 1
                }
            }
            fixture.cancellationRows(a) shouldBe 0
        }
        Then("(D-CANCEL1-008) the same rejection repeated is one row per rejection; the audit row carries no reason text") {
            val a = fixture.authorized()
            val c = a.operatorCommand(requestedBy = "opr-same-0002", approvedBy = "opr-same-0002", reason = "contains a personal note 010-0000-0000")
            repeat(3) { operator.cancel(c).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>() }
            rejections(c.approvalId.value) shouldBe 3
            fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND rejection_reason LIKE '%010%'", c.approvalId.value) shouldBe 0
        }
        Then("a payment that does not exist is PAYMENT_NOT_FOUND and still audited (CN-30 — no payment FK)") {
            val a = fixture.authorized()
            val c = a.operatorCommand(paymentId = "pay_doesnotexist0000000000")
            operator.cancel(c) shouldBe OperatorCorrectionCancelResult.NotFound
            fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND rejection_reason = 'PAYMENT_NOT_FOUND'", c.approvalId.value) shouldBe 1
        }
        // 검토 #3 m-3: 형식이 아닌 paymentId도 감사 행을 남긴다(자리표시 payment_id = MALFORMED, 사유 PAYMENT_ID_MALFORMED — D-CANCEL1-006·008).
        Then("a malformed paymentId is NotFound with one audit row (payment_id MALFORMED, reason PAYMENT_ID_MALFORMED)") {
            val a = fixture.authorized()
            val c = a.operatorCommand().copy(paymentId = "p".repeat(65), approvedTargetFingerprint = "oct1:unused") // PaymentId 상한(64) 초과 — 형식 아님
            operator.cancel(c) shouldBe OperatorCorrectionCancelResult.NotFound
            fixture.count(
                "SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND payment_id = 'MALFORMED' AND rejection_reason = 'PAYMENT_ID_MALFORMED'", c.approvalId.value,
            ) shouldBe 1
        }
        // 검토 #3 n-3(CN-32): 같은 approvalId·같은 Payment·같은 금액이어도 요청자·승인자가 다르면 같은 요청이 아니다 — 충돌로 거절하고 감사한다(fail-closed).
        Then("the same approval with another requester or approver identity is APPROVAL_IDENTITY_MISMATCH (audited), not the existing result") {
            val a = fixture.authorized()
            val c = a.operatorCommand()
            accepted(operator.cancel(c)).existing shouldBe false
            val other = a.operatorCommand(approvalId = c.approvalId.value, requestedBy = "opr-requester-99")
            operator.cancel(other).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>().code shouldBe OperatorCancelRejectionCode.APPROVAL_IDENTITY_MISMATCH
            fixture.count("SELECT COUNT(*) FROM operator_cancel_rejection WHERE approval_id = ? AND rejection_reason = 'APPROVAL_IDENTITY_MISMATCH'", c.approvalId.value) shouldBe 1
            // 같은 요청 판정은 비잠금 읽기(payment_cancellation 행 잠금 없음).
            val cancellationLocks = java.util.concurrent.atomic.AtomicInteger()
            faults.after(FaultPoint.CANCELLATION_LOCK) { cancellationLocks.incrementAndGet() }
            accepted(operator.cancel(c)).existing shouldBe true
            cancellationLocks.get() shouldBe 0
        }
        Then("a cancel in progress blocks the correction (D-CANCEL-003) and the rejection is audited") {
            val a = fixture.authorized()
            start.start(a.command()).shouldBeInstanceOf<CancelStartOutcome.Accepted>()
            val c = a.operatorCommand()
            operator.cancel(c).shouldBeInstanceOf<OperatorCorrectionCancelResult.Rejected>().code shouldBe OperatorCancelRejectionCode.CANCEL_IN_PROGRESS
            rejections(c.approvalId.value) shouldBe 1
        }
        Then("the audit row and the rejection share the TX: a failure after the audit append leaves no row and the command fails") {
            val a = fixture.authorized()
            val c = a.operatorCommand(requestedBy = "opr-same-0003", approvedBy = "opr-same-0003")
            faults.after(FaultPoint.OPERATOR_REJECTION_APPEND) { throw IllegalStateException("injected after the audit append") }
            shouldThrow<RuntimeException> { operator.cancel(c) }
            rejections(c.approvalId.value) shouldBe 0
        }
    }
})
