package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.provided.MerchantApprovalPolicy
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * 가맹점 2인 승인 실행 시간 설정(`paycore.admin.merchant-ops.approval` — MERCHANT-OPS-002 C-19, **Demo 값, 운영값 아님** — LIVE Gate). 기본값 없음: 키가 빠지면 기동 실패.
 * - [executionWindow](Demo PT1H): 승인 시각 + 이 값 = 실행 기한(배타). MASTER `max-execution-window`(Demo PT24H) 이하여야 한다(넘으면 MASTER가 APPROVAL_WINDOW_INVALID 저장).
 * - [deadlineGrace](Demo PT2M): 기한 + 이 값이 지나야 회복 job이 기한 확인을 보낸다(MASTER skew PT1M + read timeout 이상).
 * - [backoffInitial]·[backoffMax](Demo PT30S·PT30M): 회복 다음 시도 간격(두 배씩, 상한).
 * [staleAfter]는 master-ops connect + read timeout + 30초에서 [MasterOpsConfiguration]이 정한다.
 */
@ConfigurationProperties(prefix = "paycore.admin.merchant-ops.approval")
class MerchantApprovalSettings(
    executionWindow: Duration?,
    deadlineGrace: Duration?,
    backoffInitial: Duration?,
    backoffMax: Duration?,
    masterMaxExecutionWindow: Duration?,
) {
    val executionWindow: Duration = positive(executionWindow, "execution-window")
    val deadlineGrace: Duration = positive(deadlineGrace, "deadline-grace")
    val backoffInitial: Duration = positive(backoffInitial, "backoff-initial")
    val backoffMax: Duration = positive(backoffMax, "backoff-max")

    /**
     * B2-2 리뷰 n-1: MASTER `paycore.master.admin-channel.approval.max-execution-window`의 **거울 값**(Demo PT24H — PAYMENT payment-max-execution-window 선례). 실행 창이 이보다 크면
     * MASTER가 모든 승인을 APPROVAL_WINDOW_INVALID로 저장해 승인이 소진되므로 기동 시 막는다. 두 값을 함께 바꾸는 것은 배포 절차(LIVE Gate).
     */
    val masterMaxExecutionWindow: Duration = positive(masterMaxExecutionWindow, "master-max-execution-window")

    init {
        require(this.backoffInitial <= this.backoffMax) { "paycore.admin.merchant-ops.approval.backoff-initial must not exceed backoff-max" }
        require(this.executionWindow <= this.masterMaxExecutionWindow) {
            "paycore.admin.merchant-ops.approval.execution-window must not exceed master-max-execution-window (MASTER would refuse every approval)"
        }
    }

    /** [MerchantApprovalPolicy] — master-ops timeout에서 stale 지연을 더한 값. 기동 검사: stale < 실행 창, backoff 시작 > stale 전 응답 대기(connect + read). */
    fun policy(connectTimeout: Duration, readTimeout: Duration): MerchantApprovalPolicy {
        val stale = connectTimeout.plus(readTimeout).plusSeconds(STALE_MARGIN_SECONDS)
        require(stale < executionWindow) { "paycore.admin.merchant-ops.approval: stale delay (timeouts + 30s) must be shorter than the execution window" }
        require(backoffInitial > connectTimeout.plus(readTimeout)) { "paycore.admin.merchant-ops.approval.backoff-initial must exceed connect + read timeout" }
        val settings = this
        return object : MerchantApprovalPolicy {
            override val executionWindow: Duration = settings.executionWindow
            override val staleAfter: Duration = stale
            override val deadlineGrace: Duration = settings.deadlineGrace
            override val backoffInitial: Duration = settings.backoffInitial
            override val backoffMax: Duration = settings.backoffMax
        }
    }

    override fun toString(): String = "MerchantApprovalSettings(executionWindow=$executionWindow, deadlineGrace=$deadlineGrace, backoffInitial=$backoffInitial, backoffMax=$backoffMax, masterMaxExecutionWindow=$masterMaxExecutionWindow)"

    private companion object {
        const val STALE_MARGIN_SECONDS = 30L

        fun positive(value: Duration?, name: String): Duration {
            val v = requireNotNull(value) { "paycore.admin.merchant-ops.approval.$name is required" }
            require(!v.isNegative && !v.isZero) { "paycore.admin.merchant-ops.approval.$name must be positive" }
            return v
        }
    }
}
