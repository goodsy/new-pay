package com.paycore.financial.acquiring.domain

/**
 * 매입 파일 필드 값 검사(파일 계약 v1 — ACQUIRING-001 §5, I-15). 파일은 `|` 구분·LF 줄이므로 필드 값에 구분자·개행·비ASCII가 들어갈 수 없다.
 * 위반은 [AcquisitionContractViolation](메시지에 값을 넣지 않는다 — 식별자 원문이 로그에 남지 않게).
 */
internal object AcquisitionText {
    private val ID = Regex("^[A-Za-z0-9._:#-]{1,64}$")
    private val CODE = Regex("^[A-Z0-9]{1,32}$")
    private val TOKEN = Regex("^[A-Z0-9_]{1,32}$")

    fun id(name: String, value: String) = check(name, value, ID)

    /** 기관 식별 코드(D-INST-CODE-001 — `^[A-Z0-9]{1,32}$`). */
    fun code(name: String, value: String) = check(name, value, CODE)

    /** enum 이름 같은 대문자 토큰. */
    fun token(name: String, value: String) = check(name, value, TOKEN)

    private fun check(name: String, value: String, pattern: Regex) {
        if (!pattern.matches(value)) throw AcquisitionContractViolation("$name has a value outside the acquisition file v1 field grammar")
    }
}

/** 매입 파일 계약·불변식 위반(존재할 수 없는 레코드·헤더·파일). 메시지에 받은 값을 넣지 않는다. */
class AcquisitionContractViolation(message: String) : RuntimeException(message, null, false, false)
