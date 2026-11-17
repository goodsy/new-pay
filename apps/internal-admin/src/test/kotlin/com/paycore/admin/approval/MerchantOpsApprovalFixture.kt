package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.LoginReadyAccounts
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.account.domain.AdminRole
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.DecideApproval
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.OwnerServiceApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantContractApproval
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply
import com.paycore.admin.support.TestTotp
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 가맹점 계약 승인 Named Fixture(MERCHANT-OPS-002 B2-2 — 요청·승인 Use Case 경로로만, 직접 INSERT 없음). MASTER는 실제 HTTP 대역 [StubMasterOpsServer]. Spring bean이 아니다.
 * 명시 값(Demo): 가맹점마다 새 ID, 계약 `ctr_<같은 ULID>`, Revision 1 승인됨·Revision 2 **승인 전**(2026-11-11 00:00 KST 시작, 종료 없음), Revision 등록자 [StubMasterOpsServer.REGISTRANT],
 * 근거 참조 `CONTRACT-2026/0001#p1`. 실행 경로는 모든 승인이 공유하므로 approvalId별 응답을 [executeReplies]에 둔다(없으면 503 TEMPORARILY_UNAVAILABLE).
 */
class MerchantOpsApprovalFixture(
    private val merchants: MerchantOpsApprovalUseCase,
    private val owners: OwnerServiceApprovalUseCase,
    private val path: ApprovalPath,
    private val ready: LoginReadyAccounts,
    private val storage: AdminAccountStorage,
) {
    private val json = JsonMapper.builder().build()
    private val seq = AtomicLong(System.nanoTime() % 1_000_000_000L * 1000 + 700)
    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    val executeReplies = ConcurrentHashMap<String, (ByteArray) -> Reply>()
    val deadlineReplies = ConcurrentHashMap<String, (ByteArray) -> Reply>()
    val decisionReplies = ConcurrentHashMap<String, Reply>()

    fun install() {
        StubMasterOpsServer.start()
        StubMasterOpsServer.replyWith(StubMasterOpsServer.CONTRACT_REVISION_APPROVALS) { body ->
            executeReplies[idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
        }
        // B2-3: 첫 MID 발급 실행 경로도 같은 approvalId별 응답 표를 쓴다.
        StubMasterOpsServer.replyWith(StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS) { body ->
            executeReplies[idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}")
        }
        StubMasterOpsServer.replyWith(StubMasterOpsServer.DEADLINE_CONFIRMATIONS) { body ->
            deadlineReplies[idOf(body)]?.invoke(body) ?: Reply(200, "{\"decision\":\"NOT_YET_DUE\"}")
        }
        StubMasterOpsServer.replyWith(StubMasterOpsServer.APPROVAL_DECISIONS) { body -> decisionReplies[idOf(body)] ?: Reply(200, "{\"decision\":\"NONE\"}") }
    }

    /** 다른 spec의 정적 응답을 가리지 않게 동적 응답을 지운다(afterSpec). */
    fun uninstall() {
        listOf(
            StubMasterOpsServer.CONTRACT_REVISION_APPROVALS, StubMasterOpsServer.FIRST_DEFAULT_MERCHANT_ACCOUNTS, StubMasterOpsServer.DEADLINE_CONFIRMATIONS,
            StubMasterOpsServer.APPROVAL_DECISIONS,
        ).forEach(StubMasterOpsServer::clearDynamic)
    }

    fun idOf(body: ByteArray): String = json.readTree(body).get("approvalId").asString()

    fun newMerchantId(): String {
        var n = seq.incrementAndGet()
        val chars = CharArray(26) { '0' }
        var i = 25
        while (n > 0 && i >= 0) {
            chars[i--] = alphabet[(n % 32).toInt()]
            n /= 32
        }
        return "mer_" + String(chars)
    }

    fun contractOf(merchantId: String) = "ctr_" + merchantId.removePrefix("mer_")

    /** MASTER 상세: Revision 1 승인됨 + Revision 2(승인 전 — [approvedAt]·[effectiveTo]·[registeredBy]로 무효 조건을 만든다). */
    fun detail(
        merchantId: String, registeredBy: String = StubMasterOpsServer.REGISTRANT, approvedAt: String? = null, effectiveTo: String? = null,
        effectiveFrom: String = "2026-11-10T15:00:00.000Z", merchantCode: String? = DEFAULT_CODE,
    ) {
        val revisions = StubMasterOpsServer.revisionJson(1) + "," +
            StubMasterOpsServer.revisionJson(2, approvedAt = approvedAt, effectiveFrom = effectiveFrom, effectiveTo = effectiveTo, registeredBy = registeredBy)
        // MERCHANT-CODE-001 MC-3a: [merchantCode]로 현재 코드를 바꾼다(C-19 — 코드만 다른 재조회), null이면 상세에서 merchantCode 키를 뺀 Invalid 응답(모양 위반 — 요청 거절 시험).
        var json = StubMasterOpsServer.detailJson(merchantId, revisions = revisions, merchantCode = merchantCode ?: DEFAULT_CODE)
        if (merchantCode == null) json = json.replace(",\"merchantCode\":\"$DEFAULT_CODE\"}", "}")
        StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(merchantId), Reply(200, json))
    }

    fun operator(): AdminAccount = path.withRolesViaApproval(AdminRole.OPERATOR)

    fun approver(): LoginReadyAccounts.LoginReady = ready.bootstrapped(AdminRole.APPROVER)

    fun request(operator: AdminAccount, merchantId: String, revisionNo: Long = 2, evidence: String = EVIDENCE, contractId: String = contractOf(merchantId)): ApprovalOutcome =
        merchants.requestContractApproval(
            RequestMerchantContractApproval(operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, contractId, revisionNo, evidence, path.context()),
        )

    /** 정상 대상(Revision 2 승인 전)에 요청을 만든다 — (승인 ID, 가맹점 ID). */
    fun requested(operator: AdminAccount = operator(), merchantId: String = newMerchantId()): Pair<String, String> {
        detail(merchantId)
        val outcome = request(operator, merchantId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture merchant approval request failed: $outcome")).approvalId to merchantId
    }

    fun approve(approvalId: String, approver: LoginReadyAccounts.LoginReady = approver(), totp: String? = null): ApprovalOutcome = owners.approve(
        DecideApproval(approvalId, approver.account.id.value, storage.load(approver.account.id).sessionGeneration, totp ?: TestTotp.code(approver.secret), path.context()),
    )

    fun succeeded(reference: String): (ByteArray) -> Reply = { Reply(200, StubMasterOpsServer.succeeded(reference)) }

    // ------------------------------------------------------------------ B2-3 첫 Default MID 발급(§9.5)

    fun agreementOf(merchantId: String) = "csa_" + merchantId.removePrefix("mer_")
    fun siteOf(merchantId: String) = "site_" + merchantId.removePrefix("mer_")
    fun midOf(merchantId: String) = "mid_" + merchantId.removePrefix("mer_")

    /**
     * MID 발급 전 MASTER 상세: MID 0개, Revision 1([approvedAt] — 기본 승인됨, 2026-11-09 00:00 KST 시작 = 지금 적용), CSA 하나, ACTIVE WEB Site 하나(MERCHANT Scope). [withAccount]면 기존 MID가 있다.
     */
    fun firstMidDetail(
        merchantId: String, approvedAt: String? = "2026-11-09T16:00:00.000Z", withAccount: Boolean = false, registeredBy: String = StubMasterOpsServer.REGISTRANT,
        effectiveFrom: String = "2026-11-08T15:00:00.000Z",
    ) {
        val revisions = StubMasterOpsServer.revisionJson(1, approvedAt = approvedAt, effectiveFrom = effectiveFrom, registeredBy = registeredBy)
        var json = StubMasterOpsServer.detailJson(merchantId, revisions = revisions)
        if (!withAccount) json = json.replace(Regex("\"accounts\":\\[.*],\"limits\""), "\"accounts\":[],\"limits\"")
        StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(merchantId), Reply(200, json))
    }

    fun requestMid(
        operator: AdminAccount, merchantId: String, evidence: String = MID_EVIDENCE, agreementId: String = agreementOf(merchantId), siteId: String = siteOf(merchantId),
        revisionNo: Long = 1,
    ): ApprovalOutcome = merchants.requestFirstMidIssuance(
        com.paycore.admin.approval.application.provided.RequestMerchantFirstMid(
            operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, contractOf(merchantId), agreementId, revisionNo, siteId, evidence, path.context(),
        ),
    )

    /** MID 발급 적격 대상에 요청을 만든다 — (승인 ID, 가맹점 ID). */
    fun requestedMid(operator: AdminAccount = operator(), merchantId: String = newMerchantId()): Pair<String, String> {
        firstMidDetail(merchantId)
        val outcome = requestMid(operator, merchantId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture first-MID request failed: $outcome")).approvalId to merchantId
    }

    companion object {
        const val EVIDENCE = "CONTRACT-2026/0001#p1"
        const val MID_EVIDENCE = "MID-2026/0001#p1"

        /** MASTER 대역 상세의 기본 현재 코드(StubMasterOpsServer.detailJson 기본값과 같다 — Demo 값). */
        const val DEFAULT_CODE = "shopabcd12"
    }
}
