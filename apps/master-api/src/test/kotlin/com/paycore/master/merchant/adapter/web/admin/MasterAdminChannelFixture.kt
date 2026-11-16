package com.paycore.master.merchant.adapter.web.admin

import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * MASTER ADMIN 채널 시험 Fixture(src/test 전용). 키는 계약 golden의 **공개 시험 키**(adm-golden-k1·k2 — 운영 키 아님)와 Spring 시험 설정의 키(mops-test-k1 —
 * `src/test/resources/config/application.yml`). 서명 헤더는 golden 계약대로 만든다(구현 [AdminCallSignature.v1]를 쓰지만 golden 시험이 그 함수를 독립 생성 값과 대조한다).
 */
object MasterAdminChannelFixture {
    const val K1 = "adm-golden-k1"
    const val K2 = "adm-golden-k2"
    const val SPRING_KID = "mops-test-k1"
    const val SPRING_SECRET = "dGVzdC1vbmx5LW1hc3Rlci1hZG1pbi1jaGFubmVsLWtleS0wMDAx"
    val WINDOW: Duration = Duration.ofSeconds(300)

    val golden: JsonNode by lazy {
        JsonMapper.builder().build().readTree(
            requireNotNull(MasterAdminChannelFixture::class.java.getResourceAsStream("/contracts/admin-call-signature-v1.golden.json")).readAllBytes(),
        )
    }

    fun secret(kid: String): String = golden.get("keys").first { it.get("kid").asString() == kid }.get("secretBase64").asString()

    fun settings(kids: List<String> = listOf(K1, K2), readPermits: Int = 4, writePermits: Int = 2, labelPermits: Int = 2): AdminChannelSettings =
        AdminChannelSettings(kids.map { AdminChannelSettings.SigningKey(it, secret(it)) }, WINDOW, readPermits, writePermits, labelPermits)

    /** Spring 시험 설정(`src/test/resources/config/application.yml`)과 같은 값 — 조회 permit 4, 명령 permit 2(MERCHANT-OPS-002 C-1 Demo 값), 라벨 permit 2(TXN-LIST-ENRICH-001 §3.6 Demo 값). */
    fun springSettings(): AdminChannelSettings = AdminChannelSettings(listOf(AdminChannelSettings.SigningKey(SPRING_KID, SPRING_SECRET)), WINDOW, 4, 2, 2)

    fun header(method: String, path: String, body: ByteArray, kid: String = K1, t: Long = Instant.now().epochSecond, keys: AdminChannelSettings = settings()): String =
        "t=$t,kid=$kid,v1=${AdminCallSignature.v1(keys.key(kid)!!, method, path, t.toString(), body)}"

    /** Spring 컨텍스트(시험 설정 키 mops-test-k1)에 보내는 서명 헤더. */
    fun springHeader(method: String, path: String, body: ByteArray = ByteArray(0), t: Long = Instant.now().epochSecond): String =
        header(method, path, body, SPRING_KID, t, springSettings())
}
