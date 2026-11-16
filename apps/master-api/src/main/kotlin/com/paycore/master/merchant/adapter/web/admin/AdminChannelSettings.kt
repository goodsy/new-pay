package com.paycore.master.merchant.adapter.web.admin

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.util.Base64

/**
 * ADMIN → MASTER 내부 호출 서명 키 공간(MERCHANT-OPS-001 §3 — `paycore.master.admin-channel`). **ADMIN 전용** — 권리 채널(entitlement dispatch) 키와 바이트·kid가 겹치면
 * [AdminChannelKeySeparation]이 기동을 거부한다. **기본값이 없다**(값이 없으면 바인딩 실패 → 웹 프로세스 기동 실패). 키는 비밀 저장소·환경에서 주입하고 저장소에 두지 않는다.
 *
 * @param signingKeys 1..2개(교체 중에만 2개). kid `[A-Za-z0-9._-]{1,32}`, 비밀 Base64 → 32바이트 이상.
 * @param clockWindow 서명 시각 창(경계 포함, 정수 초, 1초..10분). Demo 300초 — 운영값 LIVE Gate.
 * @param readPermits 조회 동시 처리 상한(대기 0 — 1..64). Demo 4.
 * @param writePermits 명령(MERCHANT-OPS-002 §3.1 C-1) 동시 처리 상한(대기 0 — 1..16, 조회 permit과 별개). Demo 2. **기본값 없음**.
 * @param labelPermits 거래 목록 라벨 조회(TXN-LIST-ENRICH-001 §3.6 M-1) 동시 처리 상한(대기 0 — 1..16, 조회·명령 permit과 별개: 라벨 폭주가 목록·상세·**승인 결정 조회**를 막지 않는다).
 *   Demo 2(운영값 아님, LIVE Gate). **기본값 없음** — Demo 값은 `application.yml`에 둔다.
 */
@ConfigurationProperties(prefix = "paycore.master.admin-channel")
class AdminChannelSettings(val signingKeys: List<SigningKey>, val clockWindow: Duration, val readPermits: Int, val writePermits: Int, val labelPermits: Int) {

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
        require(writePermits in 1..MAX_WRITE_PERMITS) { "admin-channel write-permits must be within 1..$MAX_WRITE_PERMITS" }
        require(labelPermits in 1..MAX_LABEL_PERMITS) { "admin-channel label-permits must be within 1..$MAX_LABEL_PERMITS" }
    }

    fun key(keyId: String): ByteArray? = signingKeys.firstOrNull { it.keyId == keyId }?.bytes

    override fun toString(): String = "AdminChannelSettings(keys=${signingKeys.map { it.keyId }}, clockWindow=$clockWindow, readPermits=$readPermits, writePermits=$writePermits, labelPermits=$labelPermits)"

    companion object {
        const val MAX_KEYS = 2
        const val MIN_KEY_BYTES = 32
        const val MAX_PERMITS = 64
        const val MAX_WRITE_PERMITS = 16
        const val MAX_LABEL_PERMITS = 16
        val KEY_ID = Regex("^[A-Za-z0-9._-]{1,32}$")
        private val MIN_WINDOW: Duration = Duration.ofSeconds(1)
        private val MAX_WINDOW: Duration = Duration.ofMinutes(10)
    }
}
