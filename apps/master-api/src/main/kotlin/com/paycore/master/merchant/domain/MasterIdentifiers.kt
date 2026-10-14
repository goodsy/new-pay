package com.paycore.master.merchant.domain

/**
 * MASTER가 발급하는 식별자 형식(관례, MASTER-DOMAIN-001 명세 §6): `<prefix>_` + ULID 26자(Crockford base32 대문자).
 * 모두 ASCII 30자 이하라 PAYMENT의 MID·Site 컬럼(VARCHAR(64) binary)과 값 객체(빈 값·짝 없는 서로게이트 거부)에 그대로 맞는다.
 * 순번을 드러내지 않는다. 형식은 **확장만** 허용한다(축소하면 저장된 키를 복원할 수 없다). DB CHECK가 같은 형식을 강제한다.
 * 오류 메시지에 입력값을 넣지 않는다(로그 주입·입력 노출 방지).
 */
internal object MasterIdFormat {
    const val ULID_LENGTH = 26
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    fun requireValid(value: String, prefix: String, field: String) {
        val body = value.removePrefix("${prefix}_")
        require(value.startsWith("${prefix}_") && body.length == ULID_LENGTH && body.all { it in CROCKFORD }) {
            "$field must be '${prefix}_' followed by $ULID_LENGTH Crockford base32 characters"
        }
    }
}

@JvmInline
value class BusinessId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "businessId")
    }

    companion object {
        const val PREFIX = "biz"
    }
}

@JvmInline
value class MerchantId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantId")
    }

    companion object {
        const val PREFIX = "mer"
    }
}

/** PG가 발급하는 Site 식별자(PAYMENT의 merchantSiteId와 같은 값). */
@JvmInline
value class MerchantSiteId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantSiteId")
    }

    companion object {
        const val PREFIX = "site"
    }
}

/** MID(계약 계정, D-MD-003). PAYMENT의 merchantAccountId와 같은 값. */
@JvmInline
value class MerchantAccountId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "merchantAccountId")
    }

    companion object {
        const val PREFIX = "mid"
    }
}

@JvmInline
value class ContractId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "contractId")
    }

    companion object {
        const val PREFIX = "ctr"
    }
}

@JvmInline
value class ContractServiceAgreementId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "agreementId")
    }

    companion object {
        const val PREFIX = "csa"
    }
}

@JvmInline
value class SiteAssignmentId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "assignmentId")
    }

    companion object {
        const val PREFIX = "asg"
    }
}

@JvmInline
value class PaymentMethodActivationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "activationId")
    }

    companion object {
        const val PREFIX = "act"
    }
}

/** Site 적용 확인 기록 ID(D-MD-016, MASTER-SNAPSHOT-001). */
@JvmInline
value class SiteActivationConfirmationId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "confirmationId")
    }

    companion object {
        const val PREFIX = "sac"
    }
}

/** 통화 활성화 기록 ID(D-MD-018, MASTER-SNAPSHOT-001). */
@JvmInline
value class CurrencyEnablementId(val value: String) {
    init {
        MasterIdFormat.requireValid(value, PREFIX, "enablementId")
    }

    companion object {
        const val PREFIX = "cur"
    }
}

/**
 * 기관(또는 PG 심사) 근거 참조(D-MD-016·017·018, D-MS-003): 근거 문서·회신 번호 같은 **불투명 참조**만 담는다(원문·개인정보·Secret 금지).
 * 1~128자 `[A-Za-z0-9._:/#-]`. 오류 메시지에 입력값을 넣지 않는다.
 */
@JvmInline
value class EvidenceRef(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "evidenceRef must be 1..$MAX_LENGTH characters of [A-Za-z0-9._:/#-]" }
    }

    private companion object {
        const val MAX_LENGTH = 128

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '/' || c == '#' || c == '-'
    }
}

/**
 * 상태 전이·철회 사유(D-MS-002·003 "사유·행위자"). 운영자가 남기는 짧은 코드/설명: 1~200 code point, 제어문자·짝 없는 서로게이트 금지,
 * 앞뒤 공백 금지. 사유에 카드정보·Secret·개인정보를 넣지 않는다(운영 지침 — 형식으로는 강제하지 못한다).
 */
@JvmInline
value class DecisionReason(val value: String) {
    init {
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= MAX_CODE_POINTS) { "reason must be 1..$MAX_CODE_POINTS characters" }
        require(value.first() != ' ' && value.last() != ' ') { "reason must not start or end with a space" }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            require(!Character.isISOControl(c)) { "reason must not contain control characters" }
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "reason must be well-formed UTF-16" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(c)) { "reason must be well-formed UTF-16" }
            i++
        }
    }

    private companion object {
        const val MAX_CODE_POINTS = 200
    }
}

/** ISO 4217 alphabetic 통화 코드(대문자 3자, PAYMENT CurrencyCode와 같은 형식). 허용 여부는 통화 활성화 기록이 정한다(D-MD-018). */
@JvmInline
value class CurrencyCode(val value: String) {
    init {
        require(value.length == 3 && value.all { it in 'A'..'Z' }) { "currency must be an ISO 4217 alphabetic code (3 upper-case letters)" }
    }
}

/**
 * 명령을 낸 운영자/시스템의 불투명 참조(Q-ADMIN-AUTH 미결 — 인증·권한 검증 없이 **기록만** 한다). 1~64자 `[A-Za-z0-9._:@-]`.
 * 인증된 신원이 아니므로 요청자≠승인자 같은 통제의 근거로 쓰지 않는다(관리자 인증 결정 후 web adapter가 신원을 채운다).
 */
@JvmInline
value class ActorRef(val value: String) {
    init {
        require(value.length in 1..MAX_LENGTH && value.all(::isAllowed)) { "actor must be 1..$MAX_LENGTH characters of [A-Za-z0-9._:@-]" }
    }

    private companion object {
        const val MAX_LENGTH = 64

        fun isAllowed(c: Char): Boolean =
            c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '.' || c == '_' || c == ':' || c == '@' || c == '-'
    }
}

/** 표시 이름: 1~100 code point, 제어문자·짝 없는 서로게이트 금지, 앞뒤 공백 금지(암묵 trim 없음 — 그대로 거절). */
@JvmInline
value class DisplayName(val value: String) {
    init {
        require(value.isNotEmpty() && value.codePointCount(0, value.length) <= MAX_CODE_POINTS) { "displayName must be 1..$MAX_CODE_POINTS characters" }
        require(value.first() != ' ' && value.last() != ' ') { "displayName must not start or end with a space" }
        var i = 0
        while (i < value.length) {
            val c = value[i]
            require(!Character.isISOControl(c)) { "displayName must not contain control characters" }
            if (Character.isHighSurrogate(c)) {
                require(i + 1 < value.length && Character.isLowSurrogate(value[i + 1])) { "displayName must be well-formed UTF-16" }
                i += 2
                continue
            }
            require(!Character.isLowSurrogate(c)) { "displayName must be well-formed UTF-16" }
            i++
        }
    }

    private companion object {
        const val MAX_CODE_POINTS = 100
    }
}
