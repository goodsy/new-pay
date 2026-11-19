package com.paycore.admin.approval

import com.paycore.admin.account.AdminAccountStorage
import com.paycore.admin.account.domain.AdminAccount
import com.paycore.admin.approval.application.provided.ApprovalOutcome
import com.paycore.admin.approval.application.provided.MerchantOpsApprovalUseCase
import com.paycore.admin.approval.application.provided.RequestMerchantPaymentMethodActivation
import com.paycore.admin.approval.application.provided.RequestMerchantSiteReviewApproval
import com.paycore.admin.support.StubMasterOpsServer
import com.paycore.admin.support.StubMasterOpsServer.Reply

/**
 * B3-1 개통 승인·Site 심사 승인 Named Fixture(MERCHANT-OPS-002 §9.16 — 요청·승인 Use Case 경로로만, 직접 INSERT 없음). 승인·결정 조회·기한 확인 응답 표는 [base]
 * ([MerchantOpsApprovalFixture] — approvalId별)를 공유하고, B3-1 실행 경로 두 개를 같은 표에 연결한다. Spring bean이 아니다.
 * 명시 값(Demo): MID `mid_<가맹점 ULID>` ACTIVE, 개통 `act_<ULID>` **UNDER_REVIEW**(등록자 [StubMasterOpsServer.SITE_REGISTRANT]), 열린 PRIMARY Site `site_<ULID>`
 * (2026-10-09 시작, 종료 없음), 두 번째 Site `site_<ULID 끝 2자 바꿈>` 심사 결정 없음(PENDING). 근거 참조: 개통 `VAN-REPLY/2026-0001`(기관 회신), Site 심사 `PG-REVIEW/2026-0001`.
 */
class MerchantActivationApprovalFixture(
    val base: MerchantOpsApprovalFixture,
    private val merchants: MerchantOpsApprovalUseCase,
    private val path: ApprovalPath,
    private val storage: AdminAccountStorage,
) {
    fun install() {
        base.install()
        listOf(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, StubMasterOpsServer.SITE_REVIEW_APPROVALS).forEach { p ->
            StubMasterOpsServer.replyWith(p) { body -> base.executeReplies[base.idOf(body)]?.invoke(body) ?: Reply(503, "{\"error\":\"TEMPORARILY_UNAVAILABLE\"}") }
        }
    }

    fun uninstall() {
        base.uninstall()
        listOf(StubMasterOpsServer.PAYMENT_METHOD_ACTIVATION_APPROVALS, StubMasterOpsServer.SITE_REVIEW_APPROVALS).forEach(StubMasterOpsServer::clearDynamic)
    }

    fun ulid(merchantId: String) = merchantId.removePrefix("mer_")
    fun midOf(merchantId: String) = "mid_" + ulid(merchantId)
    fun activationOf(merchantId: String) = "act_" + ulid(merchantId)
    fun primaryOf(merchantId: String) = "site_" + ulid(merchantId)

    /**
     * 두 번째 Site(심사 대상) — ULID 끝 두 글자를 `Z9`로 바꾼 값. 가맹점 ULID가 이미 `Z9`로 끝나면(newMerchantId 순번상 약 1/1024)
     * PRIMARY와 같은 ID가 되어 stub 상세의 첫 Site(다른 등록자)가 고정되므로 그때는 `Z8`(FLAKY-ADMIN-SITE-FIXTURE — B1-gate1 1회 실패 원인).
     */
    fun secondSiteOf(merchantId: String) = ulid(merchantId).let { u -> "site_" + u.dropLast(2) + if (u.endsWith("Z9")) "Z8" else "Z9" }
        .also { check(it != primaryOf(merchantId)) { "fixture: second site must differ from PRIMARY" } }

    /**
     * MASTER 상세(B3-1 상태): [activationStatus]·[accountLifecycle]·[primary](열린 PRIMARY 여부)·[activationRegistrant]·[secondReview](두 번째 Site 심사 결정, null = PENDING)·
     * [secondLifecycle]·[siteRegistrant]로 무효 조건을 만든다.
     */
    @Suppress("LongParameterList")
    fun detail(
        merchantId: String, activationStatus: String? = "UNDER_REVIEW", accountLifecycle: String = "ACTIVE", primary: Boolean = true,
        activationRegistrant: String = StubMasterOpsServer.SITE_REGISTRANT, secondReview: String? = null, secondLifecycle: String = "ACTIVE",
        siteRegistrant: String = StubMasterOpsServer.SITE_REGISTRANT, primarySite: String = primaryOf(merchantId),
        merchantCode: String = MerchantOpsApprovalFixture.DEFAULT_CODE,
    ) {
        val u = ulid(merchantId)
        val sites = StubMasterOpsServer.siteJson(primaryOf(merchantId), review = null) + "," +
            StubMasterOpsServer.siteJson(secondSiteOf(merchantId), lifecycle = secondLifecycle, review = secondReview, registeredBy = siteRegistrant, identifier = "second.example.test")
        val assignments = if (primary) {
            StubMasterOpsServer.assignmentJson("asg_$u", primarySite)
        } else {
            StubMasterOpsServer.assignmentJson("asg_$u", primarySite, effectiveTo = "2026-10-10T00:00:00.000Z")
        }
        var json = StubMasterOpsServer.detailJson(
            merchantId, sites = sites, assignments = assignments, paymentMethods = StubMasterOpsServer.paymentMethodJson(activationOf(merchantId), activationStatus, activationRegistrant),
            siteConfirmations = "", merchantCode = merchantCode,
        )
        if (accountLifecycle != "ACTIVE") json = json.replace(""""merchantAccountId":"mid_$u","lifecycle":"ACTIVE"""", """"merchantAccountId":"mid_$u","lifecycle":"$accountLifecycle"""")
        StubMasterOpsServer.reply(StubMasterOpsServer.merchantPath(merchantId), Reply(200, json))
    }

    fun requestActivation(
        operator: AdminAccount, merchantId: String, evidence: String = ACTIVATION_EVIDENCE, mid: String = midOf(merchantId), activationId: String = activationOf(merchantId),
    ): ApprovalOutcome = merchants.requestPaymentMethodActivation(
        RequestMerchantPaymentMethodActivation(operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, mid, activationId, evidence, path.context()),
    )

    fun requestSiteReview(
        operator: AdminAccount, merchantId: String, evidence: String = SITE_EVIDENCE, siteId: String = secondSiteOf(merchantId), reasonCode: String = "REVIEW_PASSED",
    ): ApprovalOutcome = merchants.requestSiteReviewApproval(
        RequestMerchantSiteReviewApproval(operator.id.value, storage.load(operator.id).sessionGeneration, merchantId, siteId, reasonCode, evidence, path.context()),
    )

    /** 적격 개통 승인 요청 — (승인 ID, 가맹점 ID). */
    fun requestedActivation(operator: AdminAccount = base.operator(), merchantId: String = base.newMerchantId()): Pair<String, String> {
        detail(merchantId)
        val outcome = requestActivation(operator, merchantId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture activation request failed: $outcome")).approvalId to merchantId
    }

    /** 적격 Site 심사 승인 요청(두 번째 Site, PENDING) — (승인 ID, 가맹점 ID). */
    fun requestedSiteReview(operator: AdminAccount = base.operator(), merchantId: String = base.newMerchantId()): Pair<String, String> {
        detail(merchantId)
        val outcome = requestSiteReview(operator, merchantId)
        return ((outcome as? ApprovalOutcome.Created) ?: error("fixture site review request failed: $outcome")).approvalId to merchantId
    }

    companion object {
        const val ACTIVATION_EVIDENCE = "VAN-REPLY/2026-0001"
        const val SITE_EVIDENCE = "PG-REVIEW/2026-0001"
    }
}
