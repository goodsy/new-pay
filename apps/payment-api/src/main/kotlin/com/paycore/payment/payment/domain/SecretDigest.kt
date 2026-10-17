package com.paycore.payment.payment.domain

import java.security.MessageDigest
import java.util.HexFormat

/** 저장형으로만 남기는 원문 비밀의 종류와 저장 접두(AUTHN-001 명세 AN-22). 접두의 숫자는 규칙 버전이다. */
enum class SecretKind(val prefix: String) {
    /** 결제창 1회용 토큰(D-AUTHN-001). */
    CHECKOUT_TOKEN("cth1:"),

    /** 결제창 세션 쿠키 비밀. */
    CHECKOUT_SESSION("cks1:"),

    /** 인증 세션 state(D-AUTHN-003). */
    AUTHENTICATION_STATE("std1:"),

    /** 인증기관 1회용 결과 참조. */
    RESULT_REFERENCE("rrd1:"),
}

/**
 * 원문 비밀의 저장형 = 종류 접두 + SHA-256(UTF-8 원문) 소문자 hex 64자(D-AUTHN-001 "DB에는 hash만"). 원문은 이 값에서 되돌릴 수 없다.
 * 비교는 [MessageDigest.isEqual](길이가 같으면 시간 차 없음)로 하고, [toString]은 접두만 보여 준다(로그 비노출, 명세 §12.6).
 */
class SecretDigest private constructor(val kind: SecretKind, val value: String) {

    override fun equals(other: Any?): Boolean =
        other is SecretDigest && other.kind == kind && MessageDigest.isEqual(value.toByteArray(Charsets.US_ASCII), other.value.toByteArray(Charsets.US_ASCII))

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = "SecretDigest(${kind.prefix}…)"

    companion object {
        private val STORED = Regex("^([a-z]{3}1:)([0-9a-f]{64})$")

        /** 원문 [raw]의 저장형. 빈 원문은 거부한다. 원문 UTF-8은 엄격 인코딩(짝 없는 서로게이트 거부 — [Utf8Text]). */
        fun of(kind: SecretKind, raw: String): SecretDigest {
            require(raw.isNotEmpty()) { "secret must not be empty" }
            val digest = MessageDigest.getInstance("SHA-256").digest(Utf8Text.encode(raw))
            return SecretDigest(kind, kind.prefix + HexFormat.of().formatHex(digest))
        }

        /** 저장된 값(Mapper 경로). 알려진 접두 + 소문자 hex 64자만 받는다. 예외 메시지에 값을 넣지 않는다. */
        fun parse(stored: String): SecretDigest {
            val m = STORED.matchEntire(stored)
            val kind = m?.let { match -> SecretKind.entries.firstOrNull { it.prefix == match.groupValues[1] } }
            require(kind != null) { "not a stored secret digest" }
            return SecretDigest(kind, stored)
        }
    }
}
