package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestOperatorCancel
import com.paycore.admin.support.StubPaymentOpsServer
import com.paycore.admin.support.TestTotp
import java.util.concurrent.atomic.AtomicLong

/**
 * 소유 서비스 승인 Named Fixture(ADMIN-PAYMENT-OPS-001 §7.6 `OwnerApprovalFixture`) — **요청·승인 Use Case 경로로만**(직접 INSERT 없음). PAYMENT는 실제 HTTP 대역
 * [StubPaymentOpsServer]. Spring bean이 아니다(시험이 주입받은 bean으로 만든다 — @Import가 늘면 컨텍스트·MySQL 컨테이너가 하나 더 뜬다). Payment ID는 호출마다 새로 만든다(Payment 단위 미해결 UNIQUE가 시험끼리 겹치지 않게). 값은 Demo.
 */
class OwnerApprovalFixture(
    private val owners: OwnerServiceApprovalUseCase,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
) {
    private val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 300)
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun newPaymentId(): String {
        var n = seq.incrementAndGet()
        val chars = CharArray(26) { '0' }
        var i = 25
        while (n > 0 && i >= 0) {
            chars[i--] = alphabet[(n % 32).toInt()]
            n /= 32
        }
        return "pay_" + String(chars)
    }

    fun operator(): AdminAccount = path.withRolesViaApproval(AdminRole.OPERATOR)

    fun approver(): LoginReadyAccounts.LoginReady = ready.bootstrapped(AdminRole.APPROVER)

    fun request(
        operator: AdminAccount, paymentId: String, amountMinor: Long = 7_000, reason: String = "duplicate charge correction", reference: String = "RECON-2026-0001",
        purpose: String = "INCIDENT_OR_RECON_CORRECTION",
    ): ApprovalOutcome = owners.requestOperatorCancel(
        RequestOperatorCancel(operator.id.value, storage.load(operator.id).sessionGeneration, paymentId, amountMinor, purpose, reference, reason, path.context()),
    )

    /** 대상이 정상(revision 3, 반환 가능 10,000 KRW)인 Payment에 요청을 만든다 — 승인 ID. */
    fun requested(operator: AdminAccount = operator(), paymentId: String = newPaymentId(), amountMinor: Long = 7_000): Pair<String, String> {
        StubPaymentOpsServer.targetFound(paymentId)
        val outcome = request(operator, paymentId, amountMinor)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture owner request failed: $outcome")).approvalId to paymentId
    }

    fun approve(approvalId: String, approver: LoginReadyAccounts.LoginReady = approver()): ApprovalOutcome = owners.approve(
        DecideApproval(approvalId, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, TestTotp.code(approver.secret), path.context()),
    )
}
