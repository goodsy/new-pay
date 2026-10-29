package com.paycore.admin.paymentops.adapter.integration

import com.paycore.admin.paymentops.application.required.PaymentOpsTimings
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.util.Base64

/**
 * PAYMENT 운영 호출 설정(`paycore.admin.payment-ops` — ADMIN-PAYMENT-OPS-001 §4.2·§4.7·§4.16, **Demo 값, 운영값 아님** — I-24 LIVE Gate). 기본값 없음: 키가 빠지면 기동 실패.
 * - 연결: [baseUrl](경로 없음 — 서명 경로 = 요청 URI 경로, L-3), 활성 서명 키 1개([keyId], [signingKey] = Base64 ≥ 32바이트 — I-39).
 * - timeout: connect·read(F-7), 응답 읽기 상한 64 KiB(I-03 — 코드 상수 [RESPONSE_MAX_BYTES]).
 * - permit(L-8 — 대기 없음): `approval`(요청 전·승인 A 단계 조회), `lookup`(화면 조회 — 속도 제한 [lookupPerMinute]), `execution`(실행 POST·회복·대사).
 * - 시간 정책(L-6 기동 검사): `stale = connect + read + 30초`(I-22) `< backoff-max < execution-window ≤ payment-max-execution-window`(PAYMENT 설정의 거울 값 — 다르면
 *   PAYMENT가 `APPROVAL_WINDOW_INVALID`로 거절하고 경보).
 * toString에 키가 없다.
 */
@ConfigurationProperties(prefix = "paycore.admin.payment-ops")
class PaymentOpsSettings(
    baseUrl: String?,
    keyId: String?,
    signingKey: String?,
    connectTimeout: Duration?,
    readTimeout: Duration?,
    val permits: Permits,
    lookupPerMinute: Int?,
    executionWindow: Duration?,
    backoffInitial: Duration?,
    backoffMax: Duration?,
    paymentMaxExecutionWindow: Duration?,
    deadlineSkew: Duration? = null,
    recoveryBatchSize: Int? = null,
    decisionStableDelay: Duration? = null,
    reconciliationMaxPages: Int? = null,
    reconciliationFullScanInterval: Duration? = null,
) : PaymentOpsTimings {
    val baseUrl: URI = URI.create(required(baseUrl, "base-url"))
    val keyId: String = required(keyId, "key-id")
    private val key: ByteArray = decode(required(signingKey, "signing-key"))
    val connectTimeout: Duration = positive(connectTimeout, "connect-timeout")
    val readTimeout: Duration = positive(readTimeout, "read-timeout")
    override val lookupPerMinute: Int = requireNotNull(lookupPerMinute) { "paycore.admin.payment-ops.lookup-per-minute is required" }
    override val executionWindow: Duration = positive(executionWindow, "execution-window")
    override val backoffInitial: Duration = positive(backoffInitial, "backoff-initial")
    override val backoffMax: Duration = positive(backoffMax, "backoff-max")
    val paymentMaxExecutionWindow: Duration = positive(paymentMaxExecutionWindow, "payment-max-execution-window")
    override val staleAfter: Duration = this.connectTimeout.plus(this.readTimeout).plus(STALE_MARGIN)
    override val deadlineSkew: Duration = positive(deadlineSkew, "deadline-skew")
    override val recoveryBatchSize: Int = requireNotNull(recoveryBatchSize) { "paycore.admin.payment-ops.recovery-batch-size is required" }
    override val decisionStableDelay: Duration = positive(decisionStableDelay, "decision-stable-delay")
    override val reconciliationMaxPages: Int = requireNotNull(reconciliationMaxPages) { "paycore.admin.payment-ops.reconciliation-max-pages is required" }
    override val reconciliationFullScanInterval: Duration = positive(reconciliationFullScanInterval, "reconciliation-full-scan-interval")

    init {
        require(this.baseUrl.scheme == "http" || this.baseUrl.scheme == "https") { "paycore.admin.payment-ops.base-url must be http(s)" }
        require(!this.baseUrl.host.isNullOrBlank()) { "paycore.admin.payment-ops.base-url needs a host" }
        require(this.baseUrl.rawPath.isNullOrEmpty() || this.baseUrl.rawPath == "/") { "paycore.admin.payment-ops.base-url must not have a path (signed path = request path)" }
        require(this.baseUrl.rawQuery == null && this.baseUrl.rawFragment == null && this.baseUrl.rawUserInfo == null) { "paycore.admin.payment-ops.base-url: no query, fragment or user info" }
        require(KEY_ID.matches(this.keyId)) { "paycore.admin.payment-ops.key-id format" }
        require(this.recoveryBatchSize in 1..MAX_BATCH) { "paycore.admin.payment-ops.recovery-batch-size 1..$MAX_BATCH" }
        require(this.lookupPerMinute in 1..MAX_RATE) { "paycore.admin.payment-ops.lookup-per-minute 1..$MAX_RATE" }
        listOf(permits.approval, permits.lookup, permits.execution).forEach { require(it in 1..MAX_PERMITS) { "paycore.admin.payment-ops.permits 1..$MAX_PERMITS" } }
        require(this.reconciliationMaxPages in 1..MAX_PAGES) { "paycore.admin.payment-ops.reconciliation-max-pages 1..$MAX_PAGES" }
        // I-113(S6 리뷰 L-3 → S7 리뷰 L-1 Coordinator 결정): backoff-initial > connect + read(= 2 × initial > 2 × (connect + read)) — 첫 backoff가 호출 한 번의 최대 시간보다
        // 길어 늦은 응답과 다음 재전송이 겹치지 않게(여유 2배). Demo 값 initial 40초 > 2초 + 30초(운영값 아님 — LIVE Gate).
        require(this.backoffInitial > this.connectTimeout.plus(this.readTimeout)) {
            "paycore.admin.payment-ops: backoff-initial must exceed connect-timeout + read-timeout (I-113)"
        }
        require(this.backoffInitial <= this.backoffMax) { "paycore.admin.payment-ops.backoff-initial must not exceed backoff-max" }
        require(staleAfter < this.backoffMax) { "paycore.admin.payment-ops: stale (connect + read + 30s) must be below backoff-max (L-6)" }
        require(this.backoffMax < this.executionWindow) { "paycore.admin.payment-ops: backoff-max must be below execution-window (L-6)" }
        require(this.executionWindow <= this.paymentMaxExecutionWindow) { "paycore.admin.payment-ops: execution-window must not exceed payment-max-execution-window (L-6)" }
    }

    class Permits(approval: Int?, lookup: Int?, execution: Int?) {
        val approval: Int = requireNotNull(approval) { "paycore.admin.payment-ops.permits.approval is required" }
        val lookup: Int = requireNotNull(lookup) { "paycore.admin.payment-ops.permits.lookup is required" }
        val execution: Int = requireNotNull(execution) { "paycore.admin.payment-ops.permits.execution is required" }
    }

    /** 서명 키 사본(바깥에서 바꿀 수 없다). */
    fun signingKey(): ByteArray = key.copyOf()

    fun usesKey(candidateBase64: String): Boolean = runCatching { Base64.getDecoder().decode(candidateBase64) }.getOrNull()?.contentEquals(key) == true

    override fun toString(): String =
        "PaymentOpsSettings(baseUrl=$baseUrl, keyId=$keyId, signingKey=***, connectTimeout=$connectTimeout, readTimeout=$readTimeout, executionWindow=$executionWindow)"

    companion object {
        const val RESPONSE_MAX_BYTES = 64 * 1024
        val STALE_MARGIN: Duration = Duration.ofSeconds(30)
        private const val MIN_KEY_BYTES = 32
        private const val MAX_PERMITS = 64
        private const val MAX_RATE = 10_000
        private const val MAX_BATCH = 1_000
        private const val MAX_PAGES = 100
        private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")

        private fun required(value: String?, name: String): String =
            requireNotNull(value?.takeIf { it.isNotBlank() }) { "paycore.admin.payment-ops.$name is required" }

        private fun positive(value: Duration?, name: String): Duration {
            val v = requireNotNull(value) { "paycore.admin.payment-ops.$name is required" }
            require(!v.isNegative && !v.isZero) { "paycore.admin.payment-ops.$name must be positive" }
            return v
        }

        private fun decode(base64: String): ByteArray {
            val bytes = requireNotNull(runCatching { Base64.getDecoder().decode(base64) }.getOrNull()) { "paycore.admin.payment-ops.signing-key must be Base64" }
            require(bytes.size >= MIN_KEY_BYTES) { "paycore.admin.payment-ops.signing-key must be at least $MIN_KEY_BYTES bytes" }
            return bytes
        }
    }
}
