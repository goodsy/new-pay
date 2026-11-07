package com.paycore.admin.financialops.adapter.integration

import com.paycore.admin.financialops.application.provided.AcquiringOpsPolicy
import com.paycore.admin.financialops.application.required.FinancialOpsLimits
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.util.Base64

/**
 * FINANCIAL 조회 호출 설정(`paycore.admin.financial-ops` — ACQ-VIEW-001 §3.1·§4.6, **Demo 값, 운영값 아님** — LIVE Gate). 기본값 없음: 키가 빠지면 기동 실패.
 * - 연결: [baseUrl](경로·query 없음 — 서명 경로 = 요청 URI 경로), 활성 서명 키 1개([keyId], [signingKey] = Base64 ≥ 32바이트 — FINANCIAL `paycore.financial.admin-channel` 키와 같은 값).
 *   PAYMENT ops·MASTER ops 키와 kid·바이트가 같으면 기동 거부([FinancialOpsConfiguration] — 채널별 별도 키).
 * - timeout: connect·read, 응답 읽기 상한 128 KiB([RESPONSE_MAX_BYTES] — 상세 = 레코드 첫 쪽 50건 + 기록 40건, §4.6).
 * - permit: 전용 조회 permit(대기 0 — PAYMENT·MASTER ops permit과 별개: FINANCIAL 장애가 결제·가맹점 운영을 막지 않는다).
 * - 속도 제한: [lookupPerMinute](목록·상세·레코드 쪽 공용, actor별).
 * toString에 키가 없다.
 */
@ConfigurationProperties(prefix = "paycore.admin.financial-ops")
class FinancialOpsSettings(
    baseUrl: String?,
    keyId: String?,
    signingKey: String?,
    connectTimeout: Duration?,
    readTimeout: Duration?,
    permits: Int?,
    lookupPerMinute: Int?,
    operationKeyId: String? = null,
    operationSigningKey: String? = null,
    operationPermits: Int? = null,
    operationExecutionWindow: Duration? = null,
) : FinancialOpsLimits, AcquiringOpsPolicy {
    val baseUrl: URI = URI.create(required(baseUrl, "base-url"))
    val keyId: String = required(keyId, "key-id")
    private val key: ByteArray = decode(required(signingKey, "signing-key"), "signing-key")
    val connectTimeout: Duration = positive(connectTimeout, "connect-timeout")
    val readTimeout: Duration = positive(readTimeout, "read-timeout")
    val permits: Int = requireNotNull(permits) { "paycore.admin.financial-ops.permits is required" }
    override val lookupPerMinute: Int = requireNotNull(lookupPerMinute) { "paycore.admin.financial-ops.lookup-per-minute is required" }

    /** 조작 전용 키(ACQ-ADMIN-001 D-ACQA-001 — FINANCIAL `admin-channel.operation-keys`와 같은 값, 조회 키와 kid·바이트가 달라야 기동 — [FinancialOpsConfiguration]). */
    val operationKeyId: String = required(operationKeyId, "operation-key-id")
    private val operationKey: ByteArray = decode(required(operationSigningKey, "operation-signing-key"), "operation-signing-key")
    val operationPermits: Int = requireNotNull(operationPermits) { "paycore.admin.financial-ops.operation-permits is required" }
    override val executionWindow: Duration = positive(operationExecutionWindow, "operation-execution-window")
    override val staleAfter: Duration get() = connectTimeout.plus(readTimeout).plusSeconds(STALE_MARGIN_SECONDS)

    init {
        require(KEY_ID.matches(this.operationKeyId)) { "paycore.admin.financial-ops.operation-key-id format" }
        require(this.operationPermits in 1..MAX_PERMITS) { "paycore.admin.financial-ops.operation-permits 1..$MAX_PERMITS" }
        require(staleAfter < executionWindow) { "paycore.admin.financial-ops: stale delay (timeouts + 30s) must be shorter than the operation execution window" }
    }

    fun operationSigningKey(): ByteArray = operationKey.copyOf()

    fun operationUsesKey(candidateBase64: String): Boolean =
        runCatching { Base64.getDecoder().decode(candidateBase64) }.getOrNull()?.contentEquals(operationKey) == true

    init {
        require(this.baseUrl.scheme == "http" || this.baseUrl.scheme == "https") { "paycore.admin.financial-ops.base-url must be http(s)" }
        require(!this.baseUrl.host.isNullOrBlank()) { "paycore.admin.financial-ops.base-url needs a host" }
        require(this.baseUrl.rawPath.isNullOrEmpty() || this.baseUrl.rawPath == "/") { "paycore.admin.financial-ops.base-url must not have a path (signed path = request path)" }
        require(this.baseUrl.rawQuery == null && this.baseUrl.rawFragment == null && this.baseUrl.rawUserInfo == null) {
            "paycore.admin.financial-ops.base-url: no query, fragment or user info"
        }
        require(KEY_ID.matches(this.keyId)) { "paycore.admin.financial-ops.key-id format" }
        require(this.permits in 1..MAX_PERMITS) { "paycore.admin.financial-ops.permits 1..$MAX_PERMITS" }
        require(this.lookupPerMinute in 1..MAX_RATE) { "paycore.admin.financial-ops.lookup-per-minute 1..$MAX_RATE" }
    }

    /** 서명 키 사본(바깥에서 바꿀 수 없다). */
    fun signingKey(): ByteArray = key.copyOf()

    fun usesKey(candidateBase64: String): Boolean = runCatching { Base64.getDecoder().decode(candidateBase64) }.getOrNull()?.contentEquals(key) == true

    override fun toString(): String =
        "FinancialOpsSettings(baseUrl=$baseUrl, keyId=$keyId, signingKey=***, connectTimeout=$connectTimeout, readTimeout=$readTimeout, permits=$permits, " +
            "operationKeyId=$operationKeyId, operationSigningKey=***, operationPermits=$operationPermits, operationExecutionWindow=$executionWindow)"

    companion object {
        const val RESPONSE_MAX_BYTES = 128 * 1024
        private const val MIN_KEY_BYTES = 32
        private const val MAX_PERMITS = 64
        private const val MAX_RATE = 10_000
        private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")

        private fun required(value: String?, name: String): String =
            requireNotNull(value?.takeIf { it.isNotBlank() }) { "paycore.admin.financial-ops.$name is required" }

        private fun positive(value: Duration?, name: String): Duration {
            val v = requireNotNull(value) { "paycore.admin.financial-ops.$name is required" }
            require(!v.isNegative && !v.isZero) { "paycore.admin.financial-ops.$name must be positive" }
            return v
        }

        private const val STALE_MARGIN_SECONDS = 30L

        private fun decode(base64: String, name: String): ByteArray {
            val bytes = requireNotNull(runCatching { Base64.getDecoder().decode(base64) }.getOrNull()) { "paycore.admin.financial-ops.$name must be Base64" }
            require(bytes.size >= MIN_KEY_BYTES) { "paycore.admin.financial-ops.$name must be at least $MIN_KEY_BYTES bytes" }
            return bytes
        }
    }
}
