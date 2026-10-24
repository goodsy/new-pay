package com.paycore.simulator.merchant.domain

import java.time.Duration
import java.time.Instant

/**
 * 가맹점 쪽 서명 헤더 검증(WEBHOOK-001 §21.0 검증자 규칙, D-WH-003, 검토 m-8 — 가맹점 안내와 같은 규칙):
 * - 쉼표로 나눈 `key=value` 조각만 받는다(`=` 없는 조각 → MALFORMED). `t`·`kid`·`v1` 밖의 key는 무시한다.
 * - `t`는 정확히 1개, 1~10자리 10진수. 나머지(`kid`·`v1`)는 `kid` 다음 바로 `v1`인 쌍 1~2개, `v1`은 소문자 hex 64자.
 * - |now − t| ≤ [window](경계 포함 — Demo 300초)가 아니면 STALE(서명은 보지 않는다).
 * - 수신기가 아는 kid의 쌍 중 하나라도 [matches](HMAC + 상수 시간 비교 — adapter)면 ACCEPTED, 아니면 SIGNATURE.
 */
object WebhookSignatureCheck {
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val T_DIGITS = Regex("^[0-9]{1,10}$")
    private const val MAX_PAIRS = 2

    fun verify(
        header: String?,
        now: Instant,
        window: Duration,
        knownKeyIds: Set<String>,
        matches: (keyId: String, timestampSeconds: Long, v1Hex: String) -> Boolean,
    ): WebhookVerification {
        val parsed = parse(header) ?: return WebhookVerification(WebhookVerdict.MALFORMED, null)
        val (t, pairs) = parsed
        if (Math.abs(now.epochSecond - t) > window.seconds) return WebhookVerification(WebhookVerdict.STALE, null)
        val hit = pairs.firstOrNull { (kid, sig) -> kid in knownKeyIds && matches(kid, t, sig) }
        return if (hit != null) WebhookVerification(WebhookVerdict.ACCEPTED, hit.first) else WebhookVerification(WebhookVerdict.SIGNATURE, null)
    }

    private fun parse(header: String?): Pair<Long, List<Pair<String, String>>>? {
        if (header.isNullOrEmpty()) return null
        val tokens = mutableListOf<Pair<String, String>>()
        for (part in header.split(',')) {
            val eq = part.indexOf('=')
            if (eq < 0) return null
            val key = part.substring(0, eq)
            if (key == "t" || key == "kid" || key == "v1") tokens += key to part.substring(eq + 1)
        }
        val ts = tokens.filter { it.first == "t" }
        val rest = tokens.filter { it.first != "t" }
        if (ts.size != 1 || !T_DIGITS.matches(ts[0].second) || rest.size !in setOf(2, 2 * MAX_PAIRS)) return null
        val pairs = rest.chunked(2).map { (k, s) ->
            if (k.first != "kid" || s.first != "v1" || !HEX64.matches(s.second)) return null
            k.second to s.second
        }
        return ts[0].second.toLong() to pairs
    }
}
