package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.MerchantAccountLabel
import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import com.paycore.master.merchant.application.provided.MerchantHistoryPage
import java.time.Instant

/**
 * MASTER 내부 응답 모양(MERCHANT-OPS-001 §3.1–3.3). 필드 이름·순서 고정, 값 없는 필드는 `null`. view에 없는 값(기관 가맹점 번호·이력 detail·등록번호 평문)은 여기서도 만들 수 없다.
 */
object MerchantOpsJson {

    /**
     * 목록·검색(MERCHANT-CODE-001 §3.8 — 응답 모양 분리): 항목 9키 + `merchantCode` = 10키. [byCode](= 요청이 코드 검색)일 때만 `matchedRetiredCode`를 더한 11키 —
     * 목록·기존 검색 응답에 이 키가 섞이면 ADMIN `DIRECTORY`가 해석 불가(fail-closed)이므로 요청 기준으로 고른다(응답 값을 보고 고르지 않는다).
     */
    fun directory(page: MerchantDirectoryPage, byCode: Boolean = false): Map<String, Any?> = linkedMapOf(
        "items" to page.items.map {
            linkedMapOf<String, Any?>(
                "merchantId" to it.merchantId, "merchantName" to it.merchantName, "businessId" to it.businessId, "businessName" to it.businessName,
                "registrationNumberMasked" to it.registrationNumberMasked, "lifecycle" to it.lifecycle, "entitlementRevision" to it.entitlementRevision,
                "midCount" to it.midCount, "createdAt" to time(it.createdAt), "merchantCode" to it.merchantCode,
            ).also { item -> if (byCode) item["matchedRetiredCode"] = checkNotNull(it.matchedRetiredCode) { "a code search item says whether a retired code matched" } }
        },
        "nextCursor" to page.nextCursor,
    )

    /** TXN-LIST-ENRICH-001 §4.2 라벨 묶음: 항목 3키(MID·상호·현재 코드) — 내부 키·사업자·lifecycle 없음(golden `accountLabels`). */
    fun accountLabels(items: List<MerchantAccountLabel>): Map<String, Any?> = linkedMapOf(
        "items" to items.map { linkedMapOf("merchantAccountId" to it.merchantAccountId, "merchantName" to it.merchantName, "merchantCode" to it.merchantCode) },
    )

    fun history(page: MerchantHistoryPage): Map<String, Any?> = linkedMapOf(
        "items" to page.items.map {
            linkedMapOf(
                "seq" to it.seq, "occurredAt" to time(it.occurredAt), "operation" to it.operation, "aggregateType" to it.aggregateType,
                "aggregateId" to it.aggregateId, "changeType" to it.changeType, "actorRef" to it.actorRef,
            )
        },
        "nextCursor" to page.nextCursor,
    )

    fun detail(v: MerchantDetailView): Map<String, Any?> = linkedMapOf(
        "merchant" to v.merchant.let {
            linkedMapOf(
                "merchantId" to it.merchantId, "name" to it.name, "lifecycle" to it.lifecycle, "entitlementRevision" to it.entitlementRevision, "createdAt" to time(it.createdAt),
                // MERCHANT-CODE-001 §3.8: 현재 코드.
                "merchantCode" to it.merchantCode,
            )
        },
        "business" to v.business.let {
            linkedMapOf("businessId" to it.businessId, "name" to it.name, "registrationNumberMasked" to it.registrationNumberMasked, "lifecycle" to it.lifecycle)
        },
        "sites" to v.sites.map { s ->
            linkedMapOf(
                "merchantSiteId" to s.merchantSiteId, "channelType" to s.channelType, "channelIdentifier" to s.channelIdentifier, "lifecycle" to s.lifecycle,
                "review" to s.review?.let { linkedMapOf("decision" to it.decision, "decidedAt" to time(it.decidedAt)) },
                "registeredBy" to s.registeredBy,
            )
        },
        "contracts" to v.contracts.map { c ->
            linkedMapOf(
                "contractId" to c.contractId, "createdAt" to time(c.createdAt),
                "revisions" to c.revisions.map {
                    linkedMapOf(
                        "revisionNo" to it.revisionNo, "scopeKind" to it.scopeKind, "effectiveFrom" to time(it.effectiveFrom), "effectiveTo" to it.effectiveTo?.let(::time),
                        "approvedAt" to it.approvedAt?.let(::time), "scopeSiteIds" to it.scopeSiteIds, "registeredBy" to it.registeredBy,
                    )
                },
                "agreements" to c.agreements.map { linkedMapOf("agreementId" to it.agreementId, "serviceKind" to it.serviceKind, "addedAt" to time(it.addedAt)) },
            )
        },
        "accounts" to v.accounts.map(::account),
        "limits" to v.limits.map {
            linkedMapOf(
                "policyId" to it.policyId, "scopeKind" to it.scopeKind, "agreementId" to it.agreementId, "merchantAccountId" to it.merchantAccountId,
                "currencyCode" to it.currencyCode, "perTxKind" to it.perTxKind, "perTxAmount" to it.perTxAmount, "dailyKind" to it.dailyKind, "dailyAmount" to it.dailyAmount,
                "effectiveFrom" to time(it.effectiveFrom), "effectiveTo" to it.effectiveTo?.let(::time), "endedAt" to it.endedAt?.let(::time),
            )
        },
        "propagation" to v.propagation?.let {
            linkedMapOf(
                "latestRevision" to it.latestRevision, "latestRevisionIssuedAt" to time(it.latestRevisionIssuedAt), "latestAckRevision" to it.latestAckRevision,
                "latestAckAt" to it.latestAckAt?.let(::time), "effectConfirmedAt" to it.effectConfirmedAt?.let(::time), "effectBasis" to it.effectBasis?.name,
            )
        },
        // MERCHANT-CODE-001 §3.8: 코드 배정 이력(최대 2 — 승인 ID 없음)·변경 가능 여부(advisory). `registeredBy`는 두지 않는다(rev 0.3 철회).
        "merchantCodeHistory" to v.merchantCodeHistory.map {
            linkedMapOf(
                "code" to it.code, "seq" to it.seq, "source" to it.source, "replacesCode" to it.replacesCode, "assignedAt" to time(it.assignedAt),
                "assignedBy" to it.assignedBy, "reasonCode" to it.reasonCode,
            )
        },
        "merchantCodeChangeable" to v.merchantCodeChangeable,
    )

    private fun account(a: MerchantDetailView.AccountDetail): Map<String, Any?> = linkedMapOf(
        "merchantAccountId" to a.merchantAccountId, "lifecycle" to a.lifecycle, "isDefault" to a.isDefault, "issuingContractId" to a.issuingContractId,
        "issuingAgreementId" to a.issuingAgreementId, "issuingRevisionNo" to a.issuingRevisionNo, "issuedAt" to time(a.issuedAt),
        "assignments" to a.assignments.map {
            linkedMapOf(
                "assignmentId" to it.assignmentId, "merchantSiteId" to it.merchantSiteId, "role" to it.role, "status" to it.status,
                "effectiveFrom" to time(it.effectiveFrom), "effectiveTo" to it.effectiveTo?.let(::time),
            )
        },
        "paymentMethods" to a.paymentMethods.map {
            linkedMapOf(
                "activationId" to it.activationId, "paymentMethod" to it.paymentMethod, "status" to it.status, "registeredAt" to time(it.registeredAt),
                "registeredBy" to it.registeredBy,
            )
        },
        "currencies" to a.currencies.map {
            linkedMapOf(
                "enablementId" to it.enablementId, "currencyCode" to it.currencyCode, "effectiveFrom" to time(it.effectiveFrom),
                "effectiveTo" to it.effectiveTo?.let(::time), "endedAt" to it.endedAt?.let(::time),
            )
        },
        "cardRoutes" to a.cardRoutes.map {
            linkedMapOf(
                "activationId" to it.activationId, "paymentMethod" to it.paymentMethod, "acquirerCode" to it.acquirerCode, "routeRef" to it.routeRef,
                "status" to it.status, "registeredAt" to time(it.registeredAt),
            )
        },
        "siteConfirmations" to a.siteConfirmations.map {
            linkedMapOf(
                "confirmationId" to it.confirmationId, "merchantSiteId" to it.merchantSiteId, "paymentMethod" to it.paymentMethod, "activationId" to it.activationId,
                "confirmedAt" to time(it.confirmedAt), "revoked" to it.revoked,
            )
        },
    )

    private fun time(at: Instant): String = AdminChannelResponses.time(at)
}
