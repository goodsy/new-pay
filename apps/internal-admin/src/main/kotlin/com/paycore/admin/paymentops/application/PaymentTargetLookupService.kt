package com.paycore.admin.paymentops.application

import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.paymentops.application.provided.CallPurpose
import com.paycore.admin.paymentops.application.provided.LookupResult
import com.paycore.admin.paymentops.application.provided.PaymentOpsClient
import com.paycore.admin.paymentops.application.provided.PaymentTargetLookupUseCase
import com.paycore.admin.paymentops.application.provided.TargetResult
import com.paycore.admin.paymentops.application.required.PaymentOpsMetrics
import com.paycore.admin.paymentops.application.required.PaymentOpsTimings
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * [PaymentTargetLookupUseCase](§4.20·I-23): 조회자는 계정 현재 행(ACTIVE + 조회 역할), `lookup` pool, actor별 분 단위 고정 창 속도 제한(Demo 30/분 — 메모리,
 * 인스턴스별. 운영 다중 인스턴스 합산 한도는 LIVE Gate). 조회 감사 없음(I-23). 형식 검사는 PAYMENT 호출 전.
 */
@Service
class PaymentTargetLookupService(
    private val sessions: AdminSessionQuery,
    private val client: PaymentOpsClient,
    private val timings: PaymentOpsTimings,
    private val metrics: PaymentOpsMetrics,
) : PaymentTargetLookupUseCase {

    private val windows = ConcurrentHashMap<String, LongArray>()

    override fun lookup(viewerActorId: String, paymentId: String): LookupResult {
        val viewer = sessions.current(viewerActorId)
        if (viewer == null || !viewer.isActive || viewer.roles.none { it in VIEW_ROLES }) return LookupResult.Forbidden
        if (!PAYMENT_ID.matches(paymentId)) return LookupResult.InvalidInput
        if (!admit(viewerActorId, System.currentTimeMillis() / MINUTE_MS)) {
            metrics.lookupRateLimited()
            return LookupResult.RateLimited
        }
        return when (val result = client.target(paymentId, CallPurpose.LOOKUP)) {
            is TargetResult.Found -> LookupResult.Found(result.view)
            TargetResult.NotFound -> LookupResult.NotFound
            is TargetResult.Unavailable -> LookupResult.Unavailable(result.reason)
        }
    }

    /** 고정 창(분) 계수: [0] = 창 번호, [1] = 그 창의 호출 수. */
    private fun admit(actorId: String, minute: Long): Boolean {
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= timings.lookupPerMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        const val MINUTE_MS = 60_000L
    }
}
