package com.paycore.master.merchant.domain

/**
 * 기관 식별 코드 공통 형식(D-INST-CODE-001, INST-CODE-001): 매입사·Route 코드는 전 앱에서 `^[A-Z0-9]{1,32}$`(하이픈·밑줄 금지)다.
 * DB 이중 방어는 V7 `ck_card_route_activation_acquirer_code`·`ck_card_route_activation_route_code`.
 */
internal object InstitutionCodeFormat {
    private val PATTERN = Regex("^[A-Z0-9]{1,32}$")

    fun matches(value: String): Boolean = PATTERN.matches(value)
}
