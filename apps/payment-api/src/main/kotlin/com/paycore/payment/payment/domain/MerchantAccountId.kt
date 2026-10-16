package com.paycore.payment.payment.domain

/**
 * 가맹점 계정(MID) 식별자. 값은 입력 그대로 보존·비교한다: trim·대소문자 변환·Unicode 정규화를 하지 않는다.
 * 이 비정규화는 사용자 결정 D-CP-001(merchantOrderId·멱등키 대상)이 아니라 **구현 관례**다: MID는 PG(MASTER)가 발급하는 값이라
 * 정규화로 서로 다른 MID가 같아지는 일을 막는 fail-closed 선택이다(DB 키 컬럼은 같은 NO PAD binary collation, CP-DB-001).
 * MID 길이·허용 문자는 MASTER 발급 규칙(MASTER-DOMAIN-001)이라 domain은 빈 값과 짝 없는 UTF-16 서로게이트만 기술 불변식으로
 * 거부한다(CP-CONTRACT-001 §3.1: UTF-8/JDBC `?` 치환으로 서로 다른 MID가 같은 DB 키가 되는 것을 막는다).
 */
@JvmInline
value class MerchantAccountId(val value: String) {
    init {
        require(value.isNotEmpty()) { "merchantAccountId must not be empty" }
        Utf8Text.requireWellFormed(value, "merchantAccountId")
    }
}
