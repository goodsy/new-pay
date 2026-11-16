package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI
import java.time.Duration
import java.util.Base64

/**
 * MASTER 조회 호출 설정(`paycore.admin.master-ops` — MERCHANT-OPS-001 §4, **Demo 값, 운영값 아님** — LIVE Gate). 기본값 없음: 키가 빠지면 기동 실패.
 * - 연결: [baseUrl](경로·query 없음 — 서명 경로 = 요청 URI 경로), 활성 서명 키 1개([keyId], [signingKey] = Base64 ≥ 32바이트). PAYMENT ops 키와 kid·바이트가 같으면 기동 거부
 *   ([MasterOpsConfiguration]).
 * - timeout: connect·read, 응답 읽기 상한 256 KiB([RESPONSE_MAX_BYTES] — 상세가 결제 대상보다 크다).
 * - permit: 전용 조회 permit(대기 0 — PAYMENT ops permit과 별개: MASTER 장애가 승인·결제 운영을 막지 않는다).
 * - 속도 제한: [lookupPerMinute](목록·상세·이력), [registrationNumberSearchesPerMinute](등록번호 검색 — oracle 방어, 사업자 등록 명령도 1회 예약 — MERCHANT-OPS-002 C-13),
 *   [registrationsPerMinute](등록 명령 — MERCHANT-OPS-002 §4.4). 등록 명령 permit [writePermits](조회와 별개 — C-1).
 * - TXN-LIST-ENRICH-001 §3.6: 거래 목록 라벨 조회 전용 permit [labelPermits](조회 permit — 승인 결정 조회 포함 — 과 별개, 대기 0, 1..16 — M-1)와 라벨 전용 read timeout
 *   [labelReadTimeout]([readTimeout] 이하 — M-2: MASTER 지연이 목록을 일반 read timeout까지 붙잡지 않는다). 둘 다 기본값 없음.
 * toString에 키가 없다.
 */
@ConfigurationProperties(prefix = "paycore.admin.master-ops")
class MasterOpsSettings(
    baseUrl: String?,
    keyId: String?,
    signingKey: String?,
    connectTimeout: Duration?,
    readTimeout: Duration?,
    permits: Int?,
    lookupPerMinute: Int?,
    registrationNumberSearchesPerMinute: Int?,
    writePermits: Int?,
    registrationsPerMinute: Int?,
    labelPermits: Int?,
    labelReadTimeout: Duration?,
) : MerchantOpsLimits {
    val baseUrl: URI = URI.create(required(baseUrl, "base-url"))
    val keyId: String = required(keyId, "key-id")
    private val key: ByteArray = decode(required(signingKey, "signing-key"))
    val connectTimeout: Duration = positive(connectTimeout, "connect-timeout")
    val readTimeout: Duration = positive(readTimeout, "read-timeout")
    val permits: Int = requireNotNull(permits) { "paycore.admin.master-ops.permits is required" }
    override val lookupPerMinute: Int = requireNotNull(lookupPerMinute) { "paycore.admin.master-ops.lookup-per-minute is required" }
    override val registrationNumberSearchesPerMinute: Int =
        requireNotNull(registrationNumberSearchesPerMinute) { "paycore.admin.master-ops.registration-number-searches-per-minute is required" }

    /** 등록 명령 전용 permit(MERCHANT-OPS-002 C-1 — 조회 [permits]와 별개, 대기 0, 1..16). */
    val writePermits: Int = requireNotNull(writePermits) { "paycore.admin.master-ops.write-permits is required" }
    override val registrationsPerMinute: Int = requireNotNull(registrationsPerMinute) { "paycore.admin.master-ops.registrations-per-minute is required" }

    /** 라벨 조회 전용 permit(TXN-LIST-ENRICH-001 §3.6 M-1 — 대기 0, 1..16). */
    val labelPermits: Int = requireNotNull(labelPermits) { "paycore.admin.master-ops.label-permits is required" }

    /** 라벨 조회 전용 read timeout(§3.6 M-2 — [readTimeout] 이하). */
    val labelReadTimeout: Duration = positive(labelReadTimeout, "label-read-timeout")

    init {
        require(this.baseUrl.scheme == "http" || this.baseUrl.scheme == "https") { "paycore.admin.master-ops.base-url must be http(s)" }
        require(!this.baseUrl.host.isNullOrBlank()) { "paycore.admin.master-ops.base-url needs a host" }
        require(this.baseUrl.rawPath.isNullOrEmpty() || this.baseUrl.rawPath == "/") { "paycore.admin.master-ops.base-url must not have a path (signed path = request path)" }
        require(this.baseUrl.rawQuery == null && this.baseUrl.rawFragment == null && this.baseUrl.rawUserInfo == null) { "paycore.admin.master-ops.base-url: no query, fragment or user info" }
        require(KEY_ID.matches(this.keyId)) { "paycore.admin.master-ops.key-id format" }
        require(this.permits in 1..MAX_PERMITS) { "paycore.admin.master-ops.permits 1..$MAX_PERMITS" }
        require(this.lookupPerMinute in 1..MAX_RATE) { "paycore.admin.master-ops.lookup-per-minute 1..$MAX_RATE" }
        require(this.registrationNumberSearchesPerMinute in 1..MAX_BRN_RATE) {
            "paycore.admin.master-ops.registration-number-searches-per-minute 1..$MAX_BRN_RATE"
        }
        require(this.writePermits in 1..MAX_WRITE_PERMITS) { "paycore.admin.master-ops.write-permits 1..$MAX_WRITE_PERMITS" }
        require(this.registrationsPerMinute in 1..MAX_RATE) { "paycore.admin.master-ops.registrations-per-minute 1..$MAX_RATE" }
        require(this.labelPermits in 1..MAX_LABEL_PERMITS) { "paycore.admin.master-ops.label-permits 1..$MAX_LABEL_PERMITS" }
        require(this.labelReadTimeout <= this.readTimeout) { "paycore.admin.master-ops.label-read-timeout must not exceed read-timeout" }
    }

    /** 서명 키 사본(바깥에서 바꿀 수 없다). */
    fun signingKey(): ByteArray = key.copyOf()

    fun usesKey(candidateBase64: String): Boolean = runCatching { Base64.getDecoder().decode(candidateBase64) }.getOrNull()?.contentEquals(key) == true

    override fun toString(): String =
        "MasterOpsSettings(baseUrl=$baseUrl, keyId=$keyId, signingKey=***, connectTimeout=$connectTimeout, readTimeout=$readTimeout, permits=$permits, writePermits=$writePermits, labelPermits=$labelPermits, labelReadTimeout=$labelReadTimeout)"

    companion object {
        const val RESPONSE_MAX_BYTES = 256 * 1024

        /** 명령 응답 읽기 상한(MERCHANT-OPS-002 §4.1 — 명령 응답은 작다). 넘으면 해석 불가 = Unknown. */
        const val COMMAND_RESPONSE_MAX_BYTES = 4 * 1024

        /** 라벨 응답 읽기 상한(TXN-LIST-ENRICH-001 — 50항목 × 상호 100 code point 여유). 넘으면 해석 불가(UNREADABLE). */
        const val LABEL_RESPONSE_MAX_BYTES = 64 * 1024
        private const val MAX_LABEL_PERMITS = 16
        private const val MAX_WRITE_PERMITS = 16
        private const val MIN_KEY_BYTES = 32
        private const val MAX_PERMITS = 64
        private const val MAX_RATE = 10_000
        private const val MAX_BRN_RATE = 1_000
        private val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")

        private fun required(value: String?, name: String): String =
            requireNotNull(value?.takeIf { it.isNotBlank() }) { "paycore.admin.master-ops.$name is required" }

        private fun positive(value: Duration?, name: String): Duration {
            val v = requireNotNull(value) { "paycore.admin.master-ops.$name is required" }
            require(!v.isNegative && !v.isZero) { "paycore.admin.master-ops.$name must be positive" }
            return v
        }

        private fun decode(base64: String): ByteArray {
            val bytes = requireNotNull(runCatching { Base64.getDecoder().decode(base64) }.getOrNull()) { "paycore.admin.master-ops.signing-key must be Base64" }
            require(bytes.size >= MIN_KEY_BYTES) { "paycore.admin.master-ops.signing-key must be at least $MIN_KEY_BYTES bytes" }
            return bytes
        }
    }
}
