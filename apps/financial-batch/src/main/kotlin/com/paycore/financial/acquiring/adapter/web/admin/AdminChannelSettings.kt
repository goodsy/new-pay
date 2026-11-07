package com.paycore.financial.acquiring.adapter.web.admin

import org.springframework.boot.context.properties.ConfigurationProperties
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * ADMIN → FINANCIAL 내부 호출 서명 키 공간(ACQ-VIEW-001 §3.1 — `paycore.financial.admin-channel`). **이 채널 전용** — 결제 사실 Inbox 키와 바이트·kid가 겹치면
 * [AdminChannelKeySeparation]이 기동을 거부한다. **기본값이 없다**(값이 없으면 바인딩 실패 → 기동 실패, I-8). 키는 비밀 저장소·환경에서 주입하고 저장소에 두지 않는다.
 *
 * @param signingKeys 1..2개(교체 중에만 2개). kid `[A-Za-z0-9._-]{1,32}`, 비밀 Base64 → 32바이트 이상.
 * @param clockWindow 서명 시각 창(경계 포함, 정수 초, 1초..10분). Demo 300초 — 운영값 LIVE Gate.
 * @param readPermits 조회 동시 처리 상한(대기 0 — 1..16). Demo 2: Hikari pool을 결제 사실 Inbox·매입 스케줄러와 나눠 쓴다(§3.2).
 * @param operationKeys (ACQ-ADMIN-001 — 사용자 결정 D-ACQA-001 "조작 전용 키 따로") 조작(쓰기) 명령 전용 서명 키 1..2개. 조회 키와 kid·바이트가 모두 달라야 한다
 *   (같으면 기동 거부). 조작 경로(`/internal/v1/admin/acquiring-operations/`)는 이 키 서명만, 조회 경로는 조회 키 서명만 받는다.
 * @param operationPermits 조작 동시 처리 상한(대기 0 — 1..4, Demo 1).
 * @param maxExecutionWindow 승인 시각 → 실행 기한의 최대 폭(1분..7일, Demo PT24H — D-OPS-002 선례, 명세 I-2). ADMIN 실행 기한 설정과 짝(거울 값).
 */
@ConfigurationProperties(prefix = "paycore.financial.admin-channel")
class AdminChannelSettings(
    val signingKeys: List<SigningKey>,
    val clockWindow: Duration,
    val readPermits: Int,
    val operationKeys: List<SigningKey>,
    val operationPermits: Int,
    val maxExecutionWindow: Duration,
) {

    class SigningKey(val keyId: String, secret: String) {
        // 디코드 오류 메시지·원인에는 비밀 일부가 들어갈 수 있어 원인을 붙이지 않는다.
        val bytes: ByteArray = runCatching { Base64.getDecoder().decode(secret) }.getOrNull()
            ?: throw IllegalArgumentException("admin-channel signing key secret must be Base64")

        override fun toString(): String = "SigningKey(keyId=$keyId, secret=****)"
    }

    init {
        require(signingKeys.size in 1..MAX_KEYS) { "1..$MAX_KEYS admin-channel signing keys are required (two only while rotating)" }
        require(signingKeys.map { it.keyId }.toSet().size == signingKeys.size) { "admin-channel signing key ids must be unique" }
        signingKeys.forEach {
            require(KEY_ID.matches(it.keyId)) { "admin-channel signing key id must be 1..32 characters of [A-Za-z0-9._-]" }
            require(it.bytes.size >= MIN_KEY_BYTES) { "admin-channel signing key must decode to at least $MIN_KEY_BYTES bytes" }
        }
        require(clockWindow >= MIN_WINDOW && clockWindow <= MAX_WINDOW) { "admin-channel clock-window must be within 1s..10m" }
        require(clockWindow.nano == 0) { "admin-channel clock-window must be whole seconds" }
        require(readPermits in 1..MAX_PERMITS) { "admin-channel read-permits must be within 1..$MAX_PERMITS" }
        require(operationKeys.size in 1..MAX_KEYS) { "1..$MAX_KEYS admin-channel operation keys are required (two only while rotating)" }
        require(operationKeys.map { it.keyId }.toSet().size == operationKeys.size) { "admin-channel operation key ids must be unique" }
        operationKeys.forEach {
            require(KEY_ID.matches(it.keyId)) { "admin-channel operation key id must be 1..32 characters of [A-Za-z0-9._-]" }
            require(it.bytes.size >= MIN_KEY_BYTES) { "admin-channel operation key must decode to at least $MIN_KEY_BYTES bytes" }
        }
        // D-ACQA-001: 조작 키는 조회 키와 다른 키 공간이다 — kid가 겹치면 어느 공간의 서명인지 정할 수 없고, 바이트가 같으면 조회 키 유출이 곧 조작 권한이다.
        operationKeys.forEach { op ->
            signingKeys.forEach { read ->
                require(op.keyId != read.keyId) { "admin-channel operation key ids must differ from the read key ids (separate key spaces)" }
                require(!MessageDigest.isEqual(op.bytes, read.bytes)) { "admin-channel operation keys must differ from the read keys (separate key spaces)" }
            }
        }
        require(operationPermits in 1..MAX_OPERATION_PERMITS) { "admin-channel operation-permits must be within 1..$MAX_OPERATION_PERMITS" }
        require(maxExecutionWindow >= MIN_EXECUTION_WINDOW && maxExecutionWindow <= MAX_EXECUTION_WINDOW) {
            "admin-channel max-execution-window must be within 1m..7d"
        }
        require(clockWindow < maxExecutionWindow) { "admin-channel clock-window must be shorter than max-execution-window" }
    }

    /** 조회 키(권한 ADMIN_SERVICE). */
    fun key(keyId: String): ByteArray? = signingKeys.firstOrNull { it.keyId == keyId }?.bytes

    /** 조작 키(권한 ADMIN_OPERATION — D-ACQA-001). */
    fun operationKey(keyId: String): ByteArray? = operationKeys.firstOrNull { it.keyId == keyId }?.bytes

    /** 모든 키 공간(서명 검증용 조회) — kid는 두 공간에서 겹치지 않는다(기동 검사). */
    fun anyKey(keyId: String): ByteArray? = key(keyId) ?: operationKey(keyId)

    fun isOperationKey(keyId: String): Boolean = operationKeys.any { it.keyId == keyId }

    override fun toString(): String =
        "AdminChannelSettings(keys=${signingKeys.map { it.keyId }}, operationKeys=${operationKeys.map { it.keyId }}, clockWindow=$clockWindow, " +
            "readPermits=$readPermits, operationPermits=$operationPermits, maxExecutionWindow=$maxExecutionWindow)"

    companion object {
        const val MAX_KEYS = 2
        const val MIN_KEY_BYTES = 32
        const val MAX_PERMITS = 16
        const val MAX_OPERATION_PERMITS = 4
        private val MIN_EXECUTION_WINDOW: Duration = Duration.ofMinutes(1)
        private val MAX_EXECUTION_WINDOW: Duration = Duration.ofDays(7)
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
        private val MIN_WINDOW: Duration = Duration.ofSeconds(1)
        private val MAX_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
