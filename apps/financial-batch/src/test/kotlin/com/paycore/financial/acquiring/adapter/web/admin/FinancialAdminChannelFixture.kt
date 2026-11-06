package com.paycore.financial.acquiring.adapter.web.admin

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * FINANCIAL ADMIN 채널 시험 Fixture(src/test 전용 — ACQ-VIEW-001). 키는 계약 golden의 **공개 시험 키**(adm-golden-k1·k2 — 운영 키 아님)와 Spring 시험 설정의 키
 * (fops-test-k1 — `src/test/resources/config/application.yml`, 결제 사실 Inbox 시험 키와 다른 키 공간). 서명 헤더는 golden 계약대로 만든다(구현 [AdminCallSignature.v1]을 쓰지만
 * golden 시험이 그 함수를 독립 생성 값과 대조한다).
 */
object FinancialAdminChannelFixture {
    const val K1 = "adm-golden-k1"
    const val K2 = "adm-golden-k2"
    const val SPRING_KID = "fops-test-k1"
    const val SPRING_SECRET = "dGVzdC1vbmx5LWZpbmFuY2lhbC1hZG1pbi1jaGFubmVsLWtleS0wMDAx"
    val WINDOW: Duration = Duration.ofSeconds(300)

    val golden: JsonNode by lazy {
        JsonMapper.builder().build().readTree(
            requireNotNull(FinancialAdminChannelFixture::class.java.getResourceAsStream("/contracts/admin-call-signature-v1.golden.json")).readAllBytes(),
        )
    }

    fun secret(kid: String): String = golden.get("keys").first { it.get("kid").asString() == kid }.get("secretBase64").asString()

    fun settings(kids: List<String> = listOf(K1, K2), readPermits: Int = 2): AdminChannelSettings =
        AdminChannelSettings(kids.map { AdminChannelSettings.SigningKey(it, secret(it)) }, WINDOW, readPermits)

    fun springSettings(): AdminChannelSettings = AdminChannelSettings(listOf(AdminChannelSettings.SigningKey(SPRING_KID, SPRING_SECRET)), WINDOW, 2)

    fun header(method: String, path: String, body: ByteArray, kid: String = K1, t: Long = Instant.now().epochSecond, keys: AdminChannelSettings = settings()): String =
        "t=$t,kid=$kid,v1=${AdminCallSignature.v1(keys.key(kid)!!, method, path, t.toString(), body)}"

    /** Spring 컨텍스트(시험 설정 키 fops-test-k1)에 보내는 서명 헤더. */
    fun springHeader(method: String, path: String, body: ByteArray = ByteArray(0), t: Long = Instant.now().epochSecond): String =
        header(method, path, body, SPRING_KID, t, springSettings())
}
