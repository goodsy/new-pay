package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import java.time.Instant

/**
 * MERCHANT-OPS-002 B2-2 승인 명령 본문 Named Fixture(src/test 전용). ADMIN이 보내는 모양 그대로: 키 `mop1.<approvalId>`, correlationId `mop.<approvalId>`, `actor` 필드 없음,
 * 근거 6필드 + `approvalFingerprint`(기본은 같은 입력의 `mop1` 재계산 값 — 지문 계산 자체의 정답은 독립 벡터 시험이 따로 고정한다). 행위자·근거 참조는 시험 값이다.
 * 기본 시각은 시험 clock t0(2026-10-09T02:00Z) 기준 — 승인 1분 전, 기한 승인 + 1시간.
 */
object MerchantApprovalBodies {
    const val CONTRACT_REVISION_APPROVALS = "/internal/v1/admin/merchant-operations/contract-revision-approvals"
    const val APPROVAL_DECISIONS = "/internal/v1/admin/merchant-operations/approval-decisions"
    const val DEADLINE_CONFIRMATIONS = "/internal/v1/admin/merchant-operations/approval-deadline-confirmations"

    const val APPROVED_AT = "2026-10-09T01:59:00.000Z"
    const val EXECUTE_BEFORE = "2026-10-09T02:59:00.000Z"

    /** 승인 하나(ADMIN 승인 행)의 근거 값. */
    data class Approval(
        val approvalId: String = ApprovalEvidenceFixtures.approvalId(),
        val requestedBy: String = ApprovalEvidenceFixtures.REQUESTER.value,
        val approvedBy: String = ApprovalEvidenceFixtures.APPROVER.value,
        val approvedAt: String = APPROVED_AT,
        val executeBefore: String = EXECUTE_BEFORE,
        val evidenceRef: String = ApprovalEvidenceFixtures.EVIDENCE_REF,
    ) {
        val idempotencyKey: String get() = "mop1.$approvalId"
        val correlationId: String get() = "mop.$approvalId"

        fun fingerprint(operation: AdminOperation, commandFields: List<String>): String = ApprovalFingerprint.mop1(
            operation, approvalId, requestedBy, approvedBy, Instant.parse(approvedAt), Instant.parse(executeBefore), evidenceRef, commandFields,
        )
    }

    /** 필드 순서 그대로의 (이름, JSON 표기) 목록 — 무효 Fixture가 한 필드만 바꾸거나 빼는 데 쓴다. */
    fun contractRevisionApprovalFields(contractId: String, revisionNo: String = "1", a: Approval = Approval(), fingerprint: String? = null): List<Pair<String, String>> {
        val fp = fingerprint ?: a.fingerprint(AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId, revisionNo))
        return listOf(
            "idempotencyKey" to a.idempotencyKey,
            "correlationId" to a.correlationId,
            "approvalId" to a.approvalId,
            "requestedBy" to a.requestedBy,
            "approvedBy" to a.approvedBy,
            "approvedAt" to a.approvedAt,
            "executeBefore" to a.executeBefore,
            "evidenceRef" to a.evidenceRef,
            "approvalFingerprint" to fp,
            "contractId" to contractId,
            "revisionNo" to revisionNo,
        ).map { (k, v) -> k to MerchantOperationsBodies.str(v) }
    }

    fun contractRevisionApproval(contractId: String, revisionNo: String = "1", a: Approval = Approval(), fingerprint: String? = null): String =
        MerchantOperationsBodies.raw(contractRevisionApprovalFields(contractId, revisionNo, a, fingerprint))

    fun decision(approvalId: String, correlationId: String = "mop.$approvalId"): String =
        MerchantOperationsBodies.raw(listOf("approvalId" to MerchantOperationsBodies.str(approvalId), "correlationId" to MerchantOperationsBodies.str(correlationId)))

    /** B2-3 첫 Default MID 발급 실행 경로(§9.4). */
    const val FIRST_DEFAULT_MERCHANT_ACCOUNTS = "/internal/v1/admin/merchant-operations/first-default-merchant-accounts"

    /** B2-3 본문 필드(근거 공통 + 명령 필드 §9.4 순서: merchantId, contractId, agreementId, revisionNo, primarySiteId). */
    @Suppress("LongParameterList")
    fun firstDefaultIssuanceFields(
        merchantId: String, contractId: String, agreementId: String, primarySiteId: String, revisionNo: String = "1", a: Approval = Approval(), fingerprint: String? = null,
    ): List<Pair<String, String>> {
        val commandFields = listOf(merchantId, contractId, agreementId, revisionNo, primarySiteId)
        val fp = fingerprint ?: a.fingerprint(AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, commandFields)
        return listOf(
            "idempotencyKey" to a.idempotencyKey, "correlationId" to a.correlationId, "approvalId" to a.approvalId, "requestedBy" to a.requestedBy,
            "approvedBy" to a.approvedBy, "approvedAt" to a.approvedAt, "executeBefore" to a.executeBefore, "evidenceRef" to a.evidenceRef, "approvalFingerprint" to fp,
            "merchantId" to merchantId, "contractId" to contractId, "agreementId" to agreementId, "revisionNo" to revisionNo, "primarySiteId" to primarySiteId,
        ).map { (k, v) -> k to MerchantOperationsBodies.str(v) }
    }

    @Suppress("LongParameterList")
    fun firstDefaultIssuance(
        merchantId: String, contractId: String, agreementId: String, primarySiteId: String, revisionNo: String = "1", a: Approval = Approval(), fingerprint: String? = null,
    ): String = MerchantOperationsBodies.raw(firstDefaultIssuanceFields(merchantId, contractId, agreementId, primarySiteId, revisionNo, a, fingerprint))

    /** 한 필드의 JSON 표기를 바꾼다(없던 필드면 끝에 추가). */
    fun with(fields: List<Pair<String, String>>, name: String, json: String): String =
        MerchantOperationsBodies.raw(if (fields.any { it.first == name }) fields.map { if (it.first == name) name to json else it } else fields + (name to json))

    fun without(fields: List<Pair<String, String>>, name: String): String = MerchantOperationsBodies.raw(fields.filterNot { it.first == name })
}
