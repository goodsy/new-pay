package com.paycore.master.merchant.application.provided

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 통화 활성화 효력 시작(MERCHANT-OPS-002 §9.26 — D-MOPS-009 ②). 관리 채널 본문 `effectiveFrom`의 두 모드:
 *
 * - [AtExecution]: 문자열 `AT_EXECUTION`. MASTER가 잠금(merchant → merchant_account) 뒤 `lockedNow()` 값을 시작으로 **확정**한다(ADMIN 승인 시각이 아니다).
 * - [OnDate]: KST 00:00의 UTC 시각(`…Z` ms — KST 자정 검사는 web parser, C-48). 판정은 기존 Domain(`from < lockedNow` → `CURRENCY_ENABLEMENT_RETROACTIVE`).
 *
 * [text]는 본문 문자열 그대로다 — `mop1` 명령 필드 줄과 요청 지문 입력에 쓴다(확정 시각이 아니다: 같은 바이트 재전송은 실행 시각이 달라도 재생된다).
 * 새 Domain 규칙이 아니다(Application이 `from`을 정할 뿐 — §9.14).
 */
sealed interface CurrencyEffectiveStart {
    val text: String

    /** 확정 시작 시각: [AtExecution]은 [lockedNow], [OnDate]는 본문 시각. */
    fun resolve(lockedNow: Instant): Instant

    data object AtExecution : CurrencyEffectiveStart {
        override val text: String get() = AT_EXECUTION_TEXT

        override fun resolve(lockedNow: Instant): Instant = lockedNow
    }

    data class OnDate(val from: Instant) : CurrencyEffectiveStart {
        override val text: String get() = canonical(from)

        override fun resolve(lockedNow: Instant): Instant = from
    }

    companion object {
        const val AT_EXECUTION_TEXT = "AT_EXECUTION"

        private val CANONICAL: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

        /** 관리 채널 정규 ms 시각 표기(`…Z` — parser가 받은 문자열과 같다; `Instant.toString`은 0 ms를 생략하므로 쓰지 않는다). */
        fun canonical(at: Instant): String = CANONICAL.format(at)
    }
}
