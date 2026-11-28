package com.paycore.master.merchant.domain

/**
 * 제안 내용 digest(BIZ-CRIT-APPROVAL-001 §3.2 — M-5): `h1:` + HMAC-SHA256(MASTER fingerprint 키, [BusinessChangeProposal.digestInput]) 소문자 hex 64자.
 * Domain은 형식과 입력([BusinessChangeProposal.digestInput])만 정의하고 키·HMAC 계산은 adapter가 한다(키 없이 ADMIN DB 값으로 원문 대입 확인 불가).
 * 값은 로그에 남기지 않는다(toString 마스킹).
 */
@JvmInline
value class BusinessChangeDigest(val value: String) {
    init {
        require(FORMAT.matches(value)) { "content digest must match h1:<64 lowercase hex>" }
    }

    override fun toString(): String = "BusinessChangeDigest(***)"

    private companion object {
        val FORMAT = Regex("^h1:[0-9a-f]{64}$")
    }
}
