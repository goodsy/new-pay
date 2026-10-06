package com.paycore.payment.payment.domain

/**
 * 주문 환경(D-CPD-001, 사용자 확정 2026-10-06). Simulator/실기관 구분은 environment가 아니라 기관 Route 정보로 별도 관리하며
 * TEST는 실기관으로 전송하지 않는다(Route 규칙은 AUTH/연동 범위).
 */
enum class Environment { TEST, LIVE }

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

/**
 * 가맹점 주문번호. 입력 그대로 비교하고 정규화하지 않는다(D-CP-001). 형식은 D-CPC-002([MerchantRequestIdFormat])이며 생성자에서
 * 강제한다(관례, CP-CONTRACT-001 §3.3: OrderKey는 영구 DB 키라 어떤 입력 경로로도 형식 밖 값이 생기지 않게 한다).
 */
@JvmInline
value class MerchantOrderId(val value: String) {
    init {
        MerchantRequestIdFormat.requireValid(value, "merchantOrderId")
    }
}

/**
 * 가맹점이 보낸 API 멱등키(`Idempotency-Key`). 범위는 (environment, 인증된 MID, operation, key)이며 같은 키·같은 내용은 저장된
 * 최초 결과, 다른 내용은 충돌이다(시도 §5, D-CP-003). 비교는 입력 그대로(D-CP-001), 형식은 D-CPC-002. 주문 키가 아니다.
 */
@JvmInline
value class IdempotencyKey(val value: String) {
    init {
        MerchantRequestIdFormat.requireValid(value, "idempotencyKey")
    }
}

/**
 * merchantOrderId·멱등키 형식(D-CPC-002, 사용자 확정 2026-10-06): 1~64자, `[A-Za-z0-9-_.:]`만. 공백·비ASCII·제어문자는 문자 집합
 * 밖이라 거절된다. 대소문자는 구분한다(변환 없음). 오류 메시지에 입력값을 넣지 않는다(로그 주입·입력 노출 방지).
 */
internal object MerchantRequestIdFormat {
    const val MAX_LENGTH = 64

    fun isValid(value: String): Boolean = value.length in 1..MAX_LENGTH && value.all(::isAllowed)

    fun requireValid(value: String, field: String) {
        require(isValid(value)) { "$field must be 1..$MAX_LENGTH characters of [A-Za-z0-9-_.:]" }
    }

    private fun isAllowed(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == ':'
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
 * 고정 주문 입력의 fingerprint(버전 접두 포함, 예: `v1:<sha256 hex>`). 계산은 [OrderIntent.fingerprint](D-CPC-001: merchantSiteId +
 * amount + currency, 정의 CP-CONTRACT-001 §6)이며 control·Payment는 값이 같은지만 비교한다.
 * 버전 호환: v1 계산은 동결하고, 요청은 저장된 control의 버전으로 계산해 비교한다(서로 다른 버전 값을 직접 비교하면 불일치 →
 * FixedIntentConflict로 fail-closed). 외화 정산 통화·환율(Q-SETTLE-CURRENCY)은 이 비교와 별개다.
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
