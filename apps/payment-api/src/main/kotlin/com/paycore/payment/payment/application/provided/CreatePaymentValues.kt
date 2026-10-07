package com.paycore.payment.payment.application.provided

/**
 * 멱등 요청 fingerprint(CP-CONTRACT-001 §7, `r<버전>:` + SHA-256 lowercase hex). 계산은 호출자(CP-API-001 web/application)가 하고
 * 이 값은 저장·비교만 한다. 같은 멱등키의 재요청은 이 값이 같아야 저장 결과를 재생한다(다르면 IDEMPOTENCY_KEY_REUSED).
 */
@JvmInline
value class RequestFingerprint(val value: String) {
    init {
        require(PATTERN.matches(value)) { "requestFingerprint must be r<version>:<64 lowercase hex>" }
    }

    private companion object {
        val PATTERN = Regex("^r[1-9][0-9]{0,2}:[0-9a-f]{64}$")
    }
}

/**
 * Payment 생성 시점 eligibility Snapshot 버전(CP-CONTRACT-001 §13, Auditability). eligibility 판정 자체는 required Port
 * `PaymentEligibilityPort` 구현(MASTER-SNAPSHOT-001/CP-API-001)이 하며, Payment에는 그 평가 결과(Eligible)의 값만 보존한다.
 * 1..64자 출력 가능 ASCII(영속 컬럼 VARCHAR(64)).
 */
@JvmInline
value class EligibilitySnapshotVersion(val value: String) {
    init {
        require(value.length in 1..64 && value.all { it in '!'..'~' }) { "eligibilitySnapshotVersion must be 1..64 printable ASCII characters" }
    }
}

/** 멱등 요청 기록 ID(이벤트 causationId). 서버가 생성한 UUID(소문자 표준 표기). 만료 뒤 새 요청으로 갱신되면 새 ID를 쓴다. */
@JvmInline
value class IdempotencyRecordId(val value: String) {
    init {
        require(CanonicalUuid.matches(value)) { "idempotencyRecordId must be a canonical lower-case UUID" }
    }
}

/** 통합 이벤트 ID(CP-CONTRACT-001 §10 eventId). 서버가 생성한 UUID(소문자 표준 표기). 재발행 시 같은 값이다. */
@JvmInline
value class EventId(val value: String) {
    init {
        require(CanonicalUuid.matches(value)) { "eventId must be a canonical lower-case UUID" }
    }
}

private object CanonicalUuid {
    private val PATTERN = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")

    fun matches(value: String): Boolean = PATTERN.matches(value)
}

/** eligibility NotEligible 고정 분류(CP-CONTRACT-001 §9·§13). v1은 생성 시 한도를 검사하지 않으므로 한도 사유가 없다(§4). */
enum class NotEligibleReason { CURRENCY_NOT_ENABLED, METHOD_NOT_ENABLED, CONTRACT_NOT_EFFECTIVE, MERCHANT_SUSPENDED }

/** eligibility Unavailable 분류(CP-CONTRACT-001 §13, fail-closed). */
enum class EligibilityUnavailableReason { SNAPSHOT_MISSING, SNAPSHOT_EXPIRED, VERSION_UNSUPPORTED }

/** 성공 결과 종류(CP-CONTRACT-001 §5 outcome). */
enum class CreatePaymentOutcome {
    /** 이 요청이 새 Payment를 만들었다. */
    CREATED,

    /** 다른 키·같은 주문·같은 고정 입력의 활성 시도를 재사용했다. */
    EXISTING_ACTIVE_PAYMENT,
}

/**
 * 저장·재생되는 업무 거절(D-CPC-007). domain 판정 이름을 그대로 쓴다. HTTP 코드(ORDER_INTENT_CONFLICT 등) 매핑은 CP-API-001(계약 §9).
 */
enum class CreatePaymentRejection {
    FIXED_INTENT_CONFLICT,
    INCIDENT_HOLD,
    AUTHORIZED_NEW_ORDER_REQUIRED,
    TERMINATION_EVIDENCE_INSUFFICIENT,
}
