package com.paycore.master.merchant.adapter.integration

import com.paycore.master.merchant.application.required.DeploymentModePort
import com.paycore.master.merchant.application.required.RequestFingerprintDigester
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [RequestFingerprintDigester] 구현(SEC-BIZ-001 M-2 — D-MOPS-010 ②, MERCHANT-OPS-002 C-46 rev): `javax.crypto.Mac` HmacSHA256(JDK 표준 — 새 의존성 없음, Boot BOM 조합 그대로).
 * `Mac`은 thread-safe가 아니므로 호출마다 새로 만든다(키 바이트는 이 객체 안에만 — 사본을 돌려주지 않는다).
 *
 * 생성 시 기동 검사(하나라도 어기면 [IllegalStateException] → bean 생성 실패 → 기동 실패, 메시지는 규칙 이름만 — 키 원문·지문 없음):
 * - Base64, 해독 32바이트 이상.
 * - [otherSigningKeys](admin-channel 서명 키 전부 — 교체 중 2개 포함, 권리 dispatch 서명 키)와 바이트가 같으면 거부(한 키 유출이 두 용도를 동시에 깨지 않게 — C-46 n-4).
 * - LIVE(`isDemo()=false`)에서 저장소의 알려진 Demo 키(시험 설정 값의 SHA-256 목록)면 거부(L-23). Demo는 검사하지 않는다 — `deployment-mode=DEMO`로 운영에 띄우면 우회된다(구조적 한계,
 *   ADMIN `LiveModeStartupGuard`와 같은 LIVE Gate 확인 항목).
 *
 * legacy 비교 허용([legacyComparisonAllowed])은 호출마다 [DeploymentModePort.isDemo]를 읽는다.
 */
class HmacRequestFingerprintDigester(key: String, otherSigningKeys: List<ByteArray>, private val deploymentMode: DeploymentModePort) : RequestFingerprintDigester {

    private val keyBytes: ByteArray = runCatching { Base64.getDecoder().decode(key) }.getOrNull()
        ?: throw IllegalStateException("$PROPERTY must be Base64")

    init {
        check(keyBytes.size >= MIN_KEY_BYTES) { "$PROPERTY must decode to at least $MIN_KEY_BYTES bytes" }
        check(otherSigningKeys.none { MessageDigest.isEqual(it, keyBytes) }) { "$PROPERTY must differ from every MASTER signing key (separate key spaces)" }
        check(deploymentMode.isDemo() || sha256Hex(keyBytes) !in KNOWN_DEMO_KEY_FINGERPRINTS) { "LIVE deployment must not use a Demo $PROPERTY" }
    }

    override fun digest(canonical: ByteArray): ByteArray = Mac.getInstance(ALGORITHM).run {
        init(SecretKeySpec(keyBytes, ALGORITHM))
        doFinal(canonical)
    }

    override fun legacyComparisonAllowed(): Boolean = deploymentMode.isDemo()

    override fun toString(): String = "HmacRequestFingerprintDigester(key=****)"

    companion object {
        const val PROPERTY = "paycore.master.admin-channel.request-fingerprint-key"
        const val MIN_KEY_BYTES = 32
        private const val ALGORITHM = "HmacSHA256"

        /** 저장소에 공개된 Demo/시험 키(`src/test/resources/config/application.yml`)의 SHA-256. 시험 설정 키를 바꾸면 여기도 함께 바꾼다(`RequestFingerprintKeySpec`이 고정). */
        private val KNOWN_DEMO_KEY_FINGERPRINTS = setOf("c0a98afc2f4e110e58b3b1cec8dd635f91aac313fa4a21c237ea3edfe869f479")

        private fun sha256Hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    }
}
