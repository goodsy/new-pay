package com.paycore.payment.payment.adapter.web.admin

import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * ADMIN 호출 서명 시험 Fixture(ADMIN-PAYMENT-OPS-001 §7.6 `AdminChannelFixture`, src/test 전용). 키는 golden ②(`admin-call-signature-v1.golden.json`)의 **Demo 시험 키**이고
 * 시험 설정(`config/application.yml` `paycore.payment.admin-channel`)과 같다(운영 키 아님). 서명은 production [AdminCallSignature]를 쓰지 않고 여기서 따로 계산한다
 * (같은 코드로 만들고 검증하는 동어반복 방지 — golden spec이 이 계산을 golden과 대조한다).
 */
object AdminChannelFixture {
    const val K1 = "adm-golden-k1"
    const val K2 = "adm-golden-k2"
    const val HEADER = "PayCore-Admin-Signature"
    val WINDOW: Duration = Duration.ofSeconds(300)

    val golden: JsonNode by lazy { JsonMapper.builder().build().readTree(ClassPathResource("contracts/admin-call-signature-v1.golden.json").inputStream) }

    fun secret(kid: String): String = golden.get("keys").iterator().asSequence().single { it.get("kid").asString() == kid }.get("secretBase64").asString()

    /** golden 키로 만든 설정([kids] 순서, 기본 두 키 — 교체 중). */
    fun settings(kids: List<String> = listOf(K1, K2), window: Duration = WINDOW): AdminChannelSettings =
        AdminChannelSettings(kids.map { AdminChannelSettings.SigningKey(it, secret(it)) }, window)

    fun header(method: String, path: String, body: ByteArray, kid: String = K1, t: Long = Instant.now().epochSecond): String {
        val canonical = listOf("paycore.admin-call.v1", method, path, t.toString(), hex(MessageDigest.getInstance("SHA-256").digest(body))).joinToString("\n")
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(Base64.getDecoder().decode(secret(kid)), "HmacSHA256")) }
        return "t=$t,kid=$kid,v1=${hex(mac.doFinal(canonical.toByteArray(Charsets.UTF_8)))}"
    }

    private fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(bytes)
}
