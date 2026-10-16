package com.paycore.payment.payment.application.provided

import com.paycore.payment.payment.domain.Utf8Text

/**
 * 표시용 주문명 규칙(CP-API-001 명세 §4.4, 구현 결정 — 계약 §3 이월 L-8 확정). 길이 단위는 Unicode **code point** 1..[MAX_CODE_POINTS]이다:
 * 영속 컬럼 `order_name VARCHAR(100) CHARACTER SET utf8mb4`는 MySQL에서 문자(code point) 100개라 API 한계와 DB 한계가 정확히 같다
 * (UTF-16 단위면 보충 문자가 2로 세져 DB보다 좁고, UTF-8 byte 단위면 한글 100자가 거절된다). 거절: 짝 없는 서로게이트, 제어문자 Cc 전부
 * (탭·개행 포함), 줄·문단 구분자 U+2028·U+2029(로그 줄 분리), 양방향 embedding/override/isolate(U+202A–U+202E, U+2066–U+2069 — 표시 위조).
 * 그 밖의 형식 문자(이모지 결합 ZWJ 등)·공백만 있는 이름은 허용한다(trim·정규화 없음).
 */
object OrderNameRule {
    const val MAX_CODE_POINTS = 100

    fun isValid(name: String): Boolean {
        if (name.isEmpty() || !Utf8Text.isWellFormed(name)) return false
        if (name.codePointCount(0, name.length) > MAX_CODE_POINTS) return false
        return !LineSafeText.hasForbiddenCharacter(name)
    }
}
