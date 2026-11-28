package com.paycore.admin.merchantops.adapter.web

import jakarta.servlet.http.HttpSession
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.util.Base64

/**
 * 계약 담당자 **불투명 참조**(MERCHANT-FLOW-001 FLOW-2d — D-GOV-024 Q-FLOW-40 사용자 결정: 세션에 묶인 무작위 참조, 목록 응답마다 발급, 서버 세션 대응표, 세션 종료 시 무효).
 *
 * [구현 관례](가역 — 금융 의미 없음):
 * - 참조 = `mgr_` + 무작위 16바이트(`SecureRandom`) base64url 22자. 계정 키(`adm_…`)는 응답·DOM에 나가지 않고 서버 세션에만 있다.
 * - 대응표 = 세션 속성 **하나**([ATTRIBUTE])의 **문자열**(세션 '문자열만' 관례 — `StringOnlySessionConversion`): JSON `{"v":1,"exp":<epoch ms>,"refs":{"mgr_…":"adm_…"}}`.
 * - 만료 = 발급 뒤 [TTL](30분). 새 목록은 대응표를 **바꾼다**(옛 참조 무효 — 한 세션에 대응표 하나, 크기 상한 [MAX_ENTRIES] = 목록 100 + 본인).
 * - 풀기 실패(세션 없음·속성 없음·손상·만료·모르는 참조·형식 위반)는 모두 null — 호출자가 `contractManager NOT_ALLOWED`(fail-closed). 푼 계정도 Use Case가 매번 ACTIVE OPERATOR를 다시 본다.
 */
@Component
class ContractManagerReferences(private val clock: Clock) {

    @Autowired
    constructor() : this(Clock.systemUTC())

    private val random = SecureRandom()

    /** [actorIds] 순서대로 새 참조를 발급하고 이 세션의 대응표를 바꾼다. */
    fun issue(session: HttpSession, actorIds: List<String>): List<String> {
        require(actorIds.size <= MAX_ENTRIES) { "too many contract-manager candidates for one session table" }
        require(actorIds.all { ACCOUNT.matches(it) }) { "account key format" }
        val refs = actorIds.map { newRef() }
        val table = mapper.createObjectNode()
        table.put("v", VERSION)
        table.put("exp", clock.millis() + TTL.toMillis())
        val entries = table.putObject("refs")
        refs.zip(actorIds).forEach { (ref, id) -> entries.put(ref, id) }
        session.setAttribute(ATTRIBUTE, mapper.writeValueAsString(table))
        return refs
    }

    /** 이 세션 대응표에서 [ref]의 계정 키. 풀 수 없으면 null(fail-closed). */
    fun resolve(session: HttpSession?, ref: String): String? {
        if (session == null || !REF.matches(ref)) return null
        val raw = session.getAttribute(ATTRIBUTE) as? String ?: return null
        val table = runCatching { mapper.readTree(raw) as? ObjectNode }.getOrNull() ?: return null
        if (table.get("v")?.asInt() != VERSION) return null
        val exp = table.get("exp")?.takeIf { it.isIntegralNumber }?.asLong() ?: return null
        if (clock.millis() >= exp) return null
        val id = table.get("refs")?.get(ref)?.takeIf { it.isString }?.asString() ?: return null
        return id.takeIf { ACCOUNT.matches(it) }
    }

    private fun newRef(): String = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(RANDOM_BYTES).also(random::nextBytes))

    companion object {
        const val ATTRIBUTE = "paycore.merchantops.contractManagerRefs"
        private const val PREFIX = "mgr_"
        private const val VERSION = 1
        private const val RANDOM_BYTES = 16
        const val MAX_ENTRIES = 101
        val TTL: Duration = Duration.ofMinutes(30)
        val REF = Regex("^mgr_[A-Za-z0-9_-]{22}$")
        private val ACCOUNT = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
        private val mapper: JsonMapper = JsonMapper.builder().build()
    }
}
