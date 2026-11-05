package com.paycore.financial.paymentfact.domain

/**
 * PAYMENT가 확정해 보낸 결제 금융 사실 한 건(FIN-FACT-RELAY-001 §4). FINANCIAL은 이 사실을 **만들지 않고** 받아 투영한다(원천은 PAYMENT — D-ACQ-001).
 * 한 사실 = PAYMENT 확정 거래 1건([transactionId]). 업무 효과 키 [effectKey]는 PAYMENT Outbox effect_key와 같은 형식이며 FINANCIAL Inbox에서 UNIQUE다.
 *
 * 값 검사는 생성 시 끝난다(존재할 수 없는 사실 — 금액 0 이하·통화 형식·Plan 조합 — 은 [PaymentFactContractViolation]). 금융 판정(원승인 존재·합계)은
 * [PaymentFactProjectionRule]이 받은 이력과 함께 한다.
 */
sealed interface PaymentFact {
    val identity: PaymentFactIdentity
    val transactionId: String
    val authorizationId: String
    val money: FactMoney
    val kind: PaymentFactKind
    val policyAssignmentVersion: String
    val entitlementVersion: String

    /** PAYMENT Outbox effect_key 형식(`<eventType>:<transactionId>`). 봉투의 effectKey는 이 값과 같아야 한다(다르면 계약 위반). */
    val effectKey: String get() = kind.eventType + ":" + transactionId
}

/** 사실 종류와 그 PAYMENT 이벤트 이름(v1). */
enum class PaymentFactKind(val eventType: String) {
    AUTHORIZATION("PaymentAuthorized"),
    CANCELLATION("PaymentCancelled"),
    ;

    companion object {
        /** 지원하는 (eventType, schemaVersion)이면 그 종류, 아니면 null(UNSUPPORTED로 보존). */
        fun supported(eventType: String, schemaVersion: Int): PaymentFactKind? =
            if (schemaVersion == SUPPORTED_SCHEMA_VERSION) entries.firstOrNull { it.eventType == eventType } else null

        const val SUPPORTED_SCHEMA_VERSION = 1
    }
}

/** 사실이 속한 결제의 신원(키 = environment + MID + 가맹점 주문번호, 그 주문의 Payment). */
data class PaymentFactIdentity(
    val environment: FactEnvironment,
    val merchantAccountId: String,
    val merchantSiteId: String,
    val merchantOrderId: String,
    val paymentId: String,
) {
    init {
        FactText.requireId("merchantAccountId", merchantAccountId)
        FactText.requireId("merchantSiteId", merchantSiteId)
        FactText.requireId("merchantOrderId", merchantOrderId)
        FactText.requireId("paymentId", paymentId)
    }

    /** 같은 결제의 사실끼리 같아야 하는 부분(사이트는 PAYMENT 결제에 고정이지만 비교 대상에 넣는다). */
    fun sameOwner(other: PaymentFactIdentity): Boolean =
        environment == other.environment && merchantAccountId == other.merchantAccountId && merchantSiteId == other.merchantSiteId &&
            merchantOrderId == other.merchantOrderId && paymentId == other.paymentId
}

enum class FactEnvironment { TEST, LIVE }

/** 통화 minor unit 정수 금액(1 이상)과 ISO 4217 형식 통화. 통화 간 비교·합산은 같은 통화일 때만 한다. */
data class FactMoney(val minor: Long, val currency: String) {
    init {
        if (minor < 1) throw PaymentFactContractViolation("amount must be a positive minor-unit integer")
        if (!CURRENCY.matches(currency)) throw PaymentFactContractViolation("currency must be three upper-case letters")
    }

    private companion object {
        val CURRENCY = Regex("^[A-Z]{3}$")
    }
}

/** 할부 조합(PAYMENT V9 CHECK와 같다): LUMP_SUM이면 개월 없음, INSTALLMENT면 2..99. */
data class FactPlan(val kind: String, val installmentMonths: Int?) {
    init {
        val valid = (kind == LUMP_SUM && installmentMonths == null) || (kind == INSTALLMENT && installmentMonths != null && installmentMonths in 2..99)
        if (!valid) throw PaymentFactContractViolation("plan must be LUMP_SUM without months or INSTALLMENT with 2..99 months")
    }

    companion object {
        const val LUMP_SUM = "LUMP_SUM"
        const val INSTALLMENT = "INSTALLMENT"
    }
}

/** 받은 사실이 계약 형식을 어겼다(저장하지 않는다 — 수신 adapter는 422). 메시지에 받은 값을 넣지 않는다. */
class PaymentFactContractViolation(message: String) : RuntimeException(message, null, false, false)

/**
 * 사실 문자열 값의 형식 검사. PAYMENT보다 엄격하면 PAYMENT가 정상으로 확정한 사실을 FINANCIAL이 거절(격리)하게 되므로 PAYMENT 열 규칙과 같게 둔다:
 * 비어 있지 않음, 문자 수(code point) ≤ 열 길이, 짝 없는 surrogate·제어 문자(C0·DEL·C1) 없음.
 */
internal object FactText {
    fun requireId(name: String, value: String, max: Int = 64) = require(name, value, max)

    fun require(name: String, value: String, max: Int) {
        if (value.isEmpty() || value.codePointCount(0, value.length) > max || !wellFormed(value)) {
            throw PaymentFactContractViolation("$name must be 1..$max well-formed characters without control characters")
        }
    }

    private fun wellFormed(value: String): Boolean {
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= value.length || !Character.isLowSurrogate(value[i + 1])) return false
                i += 2
                continue
            }
            if (Character.isLowSurrogate(c) || Character.isISOControl(c)) return false
            i++
        }
        return true
    }

    fun requireOneOf(name: String, value: String, allowed: Set<String>) {
        if (value !in allowed) throw PaymentFactContractViolation("$name must be one of $allowed")
    }
}
