package com.paycore.payment.payment.adapter.integration.institution

/**
 * 기관 식별 코드 공통 형식(D-INST-CODE-001, INST-CODE-001): 발급사(카드사)·매입사·인증 기관·Route·VAN 기관 코드는 `^[A-Z0-9]{1,32}$`(하이픈·밑줄 금지).
 * 계약 경계(기동 설정·인증 결과 해석)에서만 검사한다 — 저장된 행의 Domain 재구성에는 쓰지 않는다(명세 §6).
 */
internal object InstitutionCodeFormat {
    private val PATTERN = Regex("^[A-Z0-9]{1,32}$")

    fun matches(value: String): Boolean = PATTERN.matches(value)
}
