package com.paycore.admin.account.application.required

import com.paycore.admin.account.domain.EnrollmentCodeHash

/** 새 1회용 등록 코드(160비트 `SecureRandom`, base32 32자 — §4.3.6). 원문은 발급 응답 1회 외 어디에도 남기지 않는다. */
interface EnrollmentCodeGenerator {
    fun newCode(): IssuedEnrollmentCode
}

/** 발급한 코드 원문(표시용, 대문자 base32 32자)과 저장할 SHA-256. toString은 원문을 내지 않는다. */
class IssuedEnrollmentCode(val displayCode: String, val hash: EnrollmentCodeHash) {
    init {
        require(EnrollmentCodeHash.normalize(displayCode) == displayCode && EnrollmentCodeHash.of(displayCode) == hash) { "display code and hash must match" }
    }

    override fun toString(): String = "IssuedEnrollmentCode(***)"
}
