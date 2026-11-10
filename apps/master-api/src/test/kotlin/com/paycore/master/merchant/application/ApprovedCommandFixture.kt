package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import com.paycore.master.merchant.application.provided.ApprovedContractRevisionApprovalCommand
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalEvidenceFixtures
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.ContractId
import java.sql.Connection

/**
 * B2-2 승인 명령 Named Fixture(application·통합 시험, src/test 전용). 근거는 [ApprovalEvidenceFixtures]의 이름 있는 근거에 같은 입력의 `mop1` 지문을 붙인다(ADMIN이 보내는 값과 같은 계산).
 * 요청 맥락은 ADMIN 계약 그대로: 키 `mop1.<approvalId>`, correlationId `mop.<approvalId>`, actor = 승인자(C-17).
 */
object ApprovedCommandFixture {

    /** MasterSchemaSpec이 DB 제약 시험용으로 직접 INSERT하는 근거 행의 표식(실행기 경로가 쓰지 않는 값 — 불변식 n-a 반대 방향 검사에서 구별). */
    const val SCHEMA_TEST_EVIDENCE_REF = "SCHEMA-TEST/direct-insert"

    fun withFingerprint(e: ApprovalEvidence, operation: AdminOperation, commandFields: List<String>): ApprovalEvidence = ApprovalEvidence(
        e.approvalId, e.requestedBy, e.approvedBy, e.approvedAt, e.executeBefore, e.evidenceRef,
        ApprovalFingerprint.mop1(operation, e.approvalId, e.requestedBy.value, e.approvedBy.value, e.approvedAt, e.executeBefore, e.evidenceRef.value, commandFields),
    )

    fun requestOf(e: ApprovalEvidence): AdminRequest = AdminRequest(AdminIdempotencyKey("mop1.${e.approvalId}"), e.approvedBy, "mop.${e.approvalId}")

    fun contractApproval(
        contractId: ContractId,
        revisionNo: Int = 1,
        evidence: ApprovalEvidence = ApprovalEvidenceFixtures.distinctActors(),
        window: ApprovalWindow = ApprovalEvidenceFixtures.DEMO_WINDOW,
    ): ApprovedContractRevisionApprovalCommand {
        val e = withFingerprint(evidence, AdminOperation.APPROVE_CONTRACT_REVISION, listOf(contractId.value, revisionNo.toString()))
        return ApprovedContractRevisionApprovalCommand(requestOf(e), e, window, contractId, revisionNo)
    }

    /** B2-3 첫 Default MID 발급의 근거 있는 명령(명령 필드 §9.4 순서 — merchantId, contractId, agreementId, revisionNo, primarySiteId). */
    fun firstDefault(
        ready: OnboardingFixture.ReadyMerchant,
        evidence: ApprovalEvidence = ApprovalEvidenceFixtures.distinctActors(),
        window: ApprovalWindow = ApprovalEvidenceFixtures.DEMO_WINDOW,
    ): com.paycore.master.merchant.application.provided.ApprovedFirstDefaultIssuanceCommand {
        val fields = listOf(ready.merchantId.value, ready.contractId.value, ready.agreementId.value, "1", ready.siteId.value)
        val e = withFingerprint(evidence, AdminOperation.ISSUE_FIRST_DEFAULT_MERCHANT_ACCOUNT, fields)
        return com.paycore.master.merchant.application.provided.ApprovedFirstDefaultIssuanceCommand(
            requestOf(e), e, window, ready.merchantId, ready.contractId, ready.agreementId, 1, ready.siteId,
        )
    }

    /** 근거 행 한 행의 주요 열(시험 단언용 — runtime 계정으로 읽는다). */
    data class EvidenceRow(
        val operation: String,
        val requestRecordId: String,
        val requestedBy: String,
        val approvedBy: String,
        val evidenceRef: String,
        val fingerprint: String,
        val outcome: String,
        val resultCode: String?,
        val resultReference: String?,
        val decidedVia: String,
        val approvedAtMillis: Long,
        val executeBeforeMillis: Long,
    )

    fun evidenceRow(c: Connection, approvalId: String): EvidenceRow? = c.prepareStatement(
        "SELECT operation, request_record_id, requested_by, approved_by, evidence_ref, approval_fingerprint, outcome, result_code, result_reference, decided_via, " +
            "approved_at, execute_before FROM admin_approval_evidence WHERE approval_id = ?",
    ).use { ps ->
        ps.setString(1, approvalId)
        ps.executeQuery().use { rs ->
            if (!rs.next()) {
                null
            } else {
                EvidenceRow(
                    rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7), rs.getString(8),
                    rs.getString(9), rs.getString(10),
                    rs.getObject(11, java.time.LocalDateTime::class.java).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                    rs.getObject(12, java.time.LocalDateTime::class.java).toInstant(java.time.ZoneOffset.UTC).toEpochMilli(),
                )
            }
        }
    }

    /**
     * 불변식 n-a(§9.3): 승인 operation(`mop1.` 키)의 `admin_request` 결과 행 전부에 같은 `request_record_id`의 근거 행이 1:1로 있다 — `APPROVAL_ID_ALREADY_USED` 거절만 제외.
     * 위반 행의 (operation, key)를 돌려준다(비어 있어야 한다). 근거 없는 seed 경로(`k-…` 키)는 D-MOPS-008 ②로 근거 행이 없는 것이 정상이라 대상이 아니다.
     */
    fun invariantViolations(c: Connection): List<String> = c.prepareStatement(
        "SELECT CONCAT(r.operation, ' ', r.idempotency_key, ' ', r.result_kind, ' ', IFNULL(r.result_code, '-'), ' rows=', " +
            "(SELECT COUNT(*) FROM admin_approval_evidence e WHERE e.request_record_id = r.request_record_id)) " +
            "FROM admin_request r WHERE r.operation IN ('ApproveContractRevision', 'IssueFirstDefaultMerchantAccount') AND r.idempotency_key LIKE 'mop1.%' " +
            "AND r.result_kind <> 'PENDING' AND (" +
            "  (IFNULL(r.result_code, '') <> 'APPROVAL_ID_ALREADY_USED' AND (SELECT COUNT(*) FROM admin_approval_evidence e WHERE e.request_record_id = r.request_record_id) <> 1)" +
            "  OR (r.result_code = 'APPROVAL_ID_ALREADY_USED' AND (SELECT COUNT(*) FROM admin_approval_evidence e WHERE e.request_record_id = r.request_record_id) <> 0))",
    ).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
}
