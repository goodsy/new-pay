package com.paycore.master.merchant.domain

enum class ChannelType { WEB, APP }

/**
 * Site 채널 식별값. 중복 판정(D-MD-008)을 위해 **소문자 정규형만** 받는다(D-MD-009 — 자동 정규화 없이 대문자·포트 등 다른 표기는 거절,
 * `www.` 등 서브도메인은 다른 채널). WEB = 도메인 host 이름만(scheme·port·path 없음, 라벨 1~63자, 전체 253자 이하, **IP 리터럴 거절** —
 * D-MD-014: 마지막 라벨이 10진 숫자이거나 `0x` 16진수면 WHATWG 기준 IPv4로 해석되므로 거절, IPv6는 `:`가 있어 형식에서 거절), APP = 점으로 구분한 앱 식별자.
 */
data class SiteChannel(val type: ChannelType, val identifier: String) {
    init {
        when (type) {
            ChannelType.WEB -> require(
                identifier.length <= MAX_LENGTH && WEB_HOST.matches(identifier) && !NUMERIC_LAST_LABEL.matches(identifier.substringAfterLast('.')),
            ) {
                "WEB channel identifier must be a lower-case domain host name (no IP literal)"
            }
            ChannelType.APP -> require(identifier.length <= MAX_LENGTH && APP_ID.matches(identifier)) {
                "APP channel identifier must be a lower-case dotted application id"
            }
        }
    }

    private companion object {
        const val MAX_LENGTH = 253
        val WEB_HOST = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)+$")
        val APP_ID = Regex("^[a-z0-9][a-z0-9_]*(\\.[a-z0-9_]+)+$")

        /**
         * WHATWG URL "ends in a number": 마지막 라벨이 10진 숫자이거나 `0x` + 16진수(빈 값 포함)면 브라우저가 IPv4로 해석한다(review n-2) — 도메인이 아니다.
         */
        val NUMERIC_LAST_LABEL = Regex("^([0-9]+|0x[0-9a-f]*)$")
    }
}
