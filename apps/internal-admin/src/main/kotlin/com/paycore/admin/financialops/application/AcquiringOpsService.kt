package com.paycore.admin.financialops.application

import com.paycore.admin.financialops.application.provided.AcquiringOpsClient
import com.paycore.admin.financialops.application.provided.AcquiringOpsDecision
import com.paycore.admin.financialops.application.provided.AcquiringOpsResult
import com.paycore.admin.financialops.application.provided.ReacquisitionCommandInput
import com.paycore.admin.financialops.application.provided.ReacquisitionCommandOutput
import com.paycore.admin.financialops.application.provided.ReacquisitionContract
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetView
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetsResult
import com.paycore.admin.financialops.application.provided.ReacquisitionTargetsView
import com.paycore.admin.financialops.application.required.AcquiringOpsGateway
import com.paycore.admin.financialops.application.required.FinancialCallResult
import com.paycore.admin.financialops.application.required.OperationCallResult
import com.paycore.admin.financialops.domain.ReacquisitionCommandBody
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * [AcquiringOpsClient]·[ReacquisitionContract](ACQ-ADMIN-001 B1-A). HTTP·서명·permit·모양 검사는 [AcquiringOpsGateway], 여기서는 FINANCIAL 응답 불변식(I-16)을 ADMIN 결과로 분류한다:
 * - 200 결정 → [AcquiringOpsResult.Decided](REJECTED도 결정 — 승인 소진), 200 결정 없음 → [AcquiringOpsResult.None].
 * - 409 `APPROVAL_CONFLICT` → Unknown + 무결성 플래그(파생 — 결정 아님, D-OPS-007 선례: 조회로만 수렴).
 * - 400 → [AcquiringOpsResult.Malformed](결정 없이 끝난 형식 거절 — 같은 바이트면 같은 판정이라 종료, 무결성 경보).
 * - 401 → Unknown(`UNAUTHENTICATED`, 운영 경보 — 키·시각). 그 밖(404·413·415·5xx·연결 실패·해석 불가·permit 없음) → Unknown(결과 불명확 — 확정 응답만 종료).
 */
@Service
class AcquiringOpsService(private val gateway: AcquiringOpsGateway) : AcquiringOpsClient, ReacquisitionContract {

    override fun targets(fileId: String, seqs: List<Int>): ReacquisitionTargetsResult = when (val r = gateway.targets(fileId, seqs)) {
        is FinancialCallResult.Found -> ReacquisitionTargetsResult.Found(view(r.document))
        FinancialCallResult.NotFound -> ReacquisitionTargetsResult.NotFound
        is FinancialCallResult.Unavailable -> ReacquisitionTargetsResult.Unavailable(r.reason)
    }

    override fun execute(approvalId: String, body: ByteArray): AcquiringOpsResult = classify(gateway.execute(approvalId, body))

    override fun decision(approvalId: String): AcquiringOpsResult = classify(gateway.decision(approvalId))

    override fun confirmDeadline(approvalId: String, body: ByteArray): AcquiringOpsResult = classify(gateway.confirmDeadline(approvalId, body))

    override fun command(input: ReacquisitionCommandInput): ReacquisitionCommandOutput {
        val body = ReacquisitionCommandBody(
            input.approvalId, input.originalFileId, input.items.map { ReacquisitionCommandBody.Item(it.seq, it.expectedGeneration, it.expectedStatus) },
            input.requestedBy, input.approvedBy, input.reasonCode, input.evidenceRef, input.approvedAt, input.executeBefore,
        )
        return ReacquisitionCommandOutput(body.contentHash(), body.json(input.correlationId))
    }

    private fun classify(result: OperationCallResult): AcquiringOpsResult = when (result) {
        is OperationCallResult.Decision -> AcquiringOpsResult.Decided(decision(result.document), result.document["confirmed"] == true)
        OperationCallResult.NoDecision -> AcquiringOpsResult.None
        is OperationCallResult.Failed -> AcquiringOpsResult.Unknown(result.reason, null, false)
        is OperationCallResult.Status -> {
            val code = result.errorCode?.takeIf { CODE.matches(it) }
            when {
                result.status == CONFLICT && code == APPROVAL_CONFLICT -> AcquiringOpsResult.Unknown(APPROVAL_CONFLICT, APPROVAL_CONFLICT, true)
                result.status == BAD_REQUEST && code != null -> AcquiringOpsResult.Malformed(code)
                result.status == UNAUTHORIZED -> AcquiringOpsResult.Unknown("UNAUTHENTICATED", null, true)
                result.status in SERVER_ERRORS && code != null -> AcquiringOpsResult.Unknown("UNAVAILABLE_$code".take(MAX_CODE), null, false)
                else -> AcquiringOpsResult.Unknown("HTTP_${result.status}", null, false)
            }
        }
    }

    private fun decision(d: Map<String, Any?>) = AcquiringOpsDecision(
        decision = d["decision"] as String, approvalId = d["approvalId"] as String, code = d["code"] as String?, targetRef = d["targetRef"] as String,
        resultFileId = d["resultFileId"] as String?, contentHash = d["contentHash"] as String, requestedBy = d["requestedBy"] as String,
        approvedBy = d["approvedBy"] as String, origin = d["origin"] as String,
    )

    @Suppress("UNCHECKED_CAST")
    private fun view(doc: Map<String, Any?>) = ReacquisitionTargetsView(
        fileId = doc["fileId"] as String, readAt = Instant.parse(doc["readAt"] as String),
        items = (doc["items"] as List<Map<String, Any?>>).map { i ->
            ReacquisitionTargetView(
                seq = (i["seq"] as Long).toInt(), transactionId = i["transactionId"] as String?, recordType = i["recordType"] as String?,
                generation = (i["generation"] as Long?)?.toInt(), status = i["status"] as String?, eligible = i["eligible"] as Boolean, refusal = i["refusal"] as String?,
                approvedAmountMinor = i["approvedAmountMinor"] as Long?, cancelledAmountMinor = i["cancelledAmountMinor"] as Long?, expectedNetMinor = i["expectedNetMinor"] as Long?,
            )
        },
    )

    private companion object {
        const val APPROVAL_CONFLICT = "APPROVAL_CONFLICT"
        const val BAD_REQUEST = 400
        const val UNAUTHORIZED = 401
        const val CONFLICT = 409
        val SERVER_ERRORS = 500..599
        const val MAX_CODE = 48
        val CODE = Regex("^[A-Z][A-Z0-9_]{0,47}$")
    }
}
