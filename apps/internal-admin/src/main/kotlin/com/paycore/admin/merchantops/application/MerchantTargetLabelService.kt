package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.merchantops.application.provided.MerchantIdRules
import com.paycore.admin.merchantops.application.provided.MerchantLabelClient
import com.paycore.admin.merchantops.application.provided.MerchantLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelResult
import com.paycore.admin.merchantops.application.provided.MerchantTargetLabelUseCase
import com.paycore.admin.merchantops.application.required.MerchantTargetLabelLimits
import com.paycore.admin.merchantops.application.required.MerchantTargetLabelMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * [MerchantTargetLabelUseCase](MERCHANT-CODE-001 §5.6.3 MC-3b). 분당 창은 **라벨 전용** [ActorMinuteWindows] 인스턴스(C-35 — `label-reads-per-minute`, HTTP 호출 1회당 1 소비,
 * 묶음 크기 무관) — 가맹점 검색 창(`MerchantLookupService`의 `lookup-per-minute`)을 쓰지 않는다. MASTER 실패는 예외가 아니라 [MerchantTargetLabelResult.Unavailable].
 * 지표 `merchant_target_labels{result}`·로그는 분류·요청 수만(키·상호·코드 없음). 감사 없음.
 */
@Service
class MerchantTargetLabelService(
    private val sessions: AdminSessionQuery,
    private val client: MerchantLabelClient,
    limits: MerchantTargetLabelLimits,
    private val metrics: MerchantTargetLabelMetrics,
) : MerchantTargetLabelUseCase {

    private val windows = ActorMinuteWindows({ limits.labelReadsPerMinute })

    override fun labels(viewerActorId: String, merchantIds: List<String>?): MerchantTargetLabelResult {
        val viewer = sessions.current(viewerActorId)?.takeIf { it.isActive && it.roles.any { role -> role in VIEW_ROLES } }
            ?: return MerchantTargetLabelResult.Forbidden.also { log("FORBIDDEN", 0) }
        if (merchantIds == null || !wellFormed(merchantIds)) return MerchantTargetLabelResult.InvalidInput.also { log("INVALID_INPUT", merchantIds?.size ?: 0) }
        if (windows.tryReserve(viewer.actorId) == null) {
            metrics.targetLabels(RATE_LIMITED)
            return MerchantTargetLabelResult.RateLimited.also { log(RATE_LIMITED, merchantIds.size) }
        }
        return when (val result = client.merchantLabels(merchantIds.sorted())) {
            is MerchantLabelResult.Found -> MerchantTargetLabelResult.Found(result.labels).also {
                metrics.targetLabels(OK)
                log(OK, merchantIds.size)
            }
            is MerchantLabelResult.Unavailable -> MerchantTargetLabelResult.Unavailable(result.failure).also {
                metrics.targetLabels(result.failure.name)
                log(result.failure.name, merchantIds.size)
            }
        }
    }

    private fun wellFormed(ids: List<String>): Boolean =
        ids.size in 1..MerchantIdRules.MAX_PER_CALL && ids.all { MerchantIdRules.FORMAT.matches(it) } && ids.toSet().size == ids.size

    private fun log(result: String, requested: Int) {
        log.info("operation=merchant-target-labels result={} requested={}", result, requested)
    }

    private companion object {
        val log = LoggerFactory.getLogger(MerchantTargetLabelService::class.java)

        /** 승인함·감사 화면을 볼 수 있는 역할 집합(§5.6.3 — 가맹점 조회 VIEW 4역할과 같다). */
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        const val OK = "OK"
        const val RATE_LIMITED = "RATE_LIMITED"
    }
}
