package com.paycore.payment.payment.domain

/**
 * 주문 환경(D-CPD-001, 사용자 확정 2026-10-06). Simulator/실기관 구분은 environment가 아니라 기관 Route 정보로 별도 관리하며
 * TEST는 실기관으로 전송하지 않는다(Route 규칙은 AUTH/연동 범위).
 */
enum class Environment { TEST, LIVE }

/**
 * 가맹점 계정(MID) 식별자. 값은 입력 그대로 보존·비교한다: trim·대소문자 변환·Unicode 정규화를 하지 않는다(D-CP-001, 사용자 확정
 * 2026-10-06 — DB 키 컬럼은 NO PAD binary collation, CP-DB-001). 길이·허용 문자와 공백만인 값의 허용 여부는 API 계약
 * (CP-CONTRACT-001) 범위라 domain은 정하지 않는다. 빈 값만 기술 불변식으로 거부한다.
 */
@JvmInline
value class MerchantAccountId(val value: String) {
    init {
        require(value.isNotEmpty()) { "merchantAccountId must not be empty" }
    }
}

/** 가맹점 주문번호. 입력 그대로 비교하고 정규화하지 않는다(D-CP-001, [MerchantAccountId]와 같음). */
@JvmInline
value class MerchantOrderId(val value: String) {
    init {
        require(value.isNotEmpty()) { "merchantOrderId must not be empty" }
    }
}

/**
 * 주문 키 = (environment, merchantAccountId, merchantOrderId) — 확정 문서 §1. 주문은 MID 범위이며 다른 MID·다른 환경은 별개의 주문이다.
 * 이 키당 OrderPaymentControl은 하나다(영속 유일성은 CP-DB의 UNIQUE 제약으로 보장한다).
 */
data class OrderKey(
    val environment: Environment,
    val merchantAccountId: MerchantAccountId,
    val merchantOrderId: MerchantOrderId,
)

/**
 * 주문 고정 입력(금액·통화·MID·Site·결제 대상 등)의 불투명 fingerprint. application이 정규화 규칙과 버전에 따라 계산하며 domain은
 * 값이 같은지만 비교한다. 이미 정해진 입력 규칙: ID는 입력 그대로(D-CP-001), 금액은 ISO 4217 통화와 통화별 minor unit 기준
 * (D-CP-002, 허용 통화는 MID·기관 계약별 기준정보, 초기 KRW·USD). 어떤 필드를 어떤 표현·버전으로 fingerprint에 넣을지는
 * CP-CONTRACT-001에서 고정한다(계산 방식이 바뀌면 기존 주문과의 비교 호환성도 그때 정한다). 외화 정산 통화·환율은
 * Q-SETTLE-CURRENCY(SETTLEMENT-CONTRACT-001)이며 주문 고정 입력 비교와는 별개다.
 */
@JvmInline
value class OrderIntentFingerprint(val value: String) {
    init {
        require(value.isNotEmpty()) { "orderIntentFingerprint must not be empty" }
    }
}

/** 서버가 생성하는 Payment 식별자(생성은 application의 Port 책임). */
@JvmInline
value class PaymentId(val value: String) {
    init {
        require(value.isNotEmpty()) { "paymentId must not be empty" }
    }
}
