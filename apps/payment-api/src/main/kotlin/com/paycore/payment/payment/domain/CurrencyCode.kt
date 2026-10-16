package com.paycore.payment.payment.domain

/**
 * ISO 4217 alphabetic 통화 코드(대문자 3자). 대소문자 변환·trim 없이 형식만 검사한다(CP-CONTRACT-001 §3·§4).
 *
 * 어떤 통화를 허용할지는 코드가 아니라 MID·기관 계약 기준정보가 정한다(D-CP-002, 사용자 확정 2026-10-06). 그래서 이 타입은 허용
 * 목록을 갖지 않는다. 초기 구현·검증 통화(KRW·USD)는 시험 fixture에만 있다.
 */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) { "currency must be an ISO 4217 alphabetic code (3 upper-case letters)" }
    }
}
