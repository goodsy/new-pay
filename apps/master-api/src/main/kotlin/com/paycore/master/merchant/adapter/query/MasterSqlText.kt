package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.LocalDateTime

/**
 * 조회 adapter 공용 SQL 표현(MERCHANT-OPS-001). 시각은 저장 UTC 벽시계를 ISO `…Z` 문자열로 읽는다(JVM·driver 시간대 변환 없음 — JooqMerchantStructureReader KDoc,
 * TZ-FIX-001). 사업자등록번호는 SQL에서 앞 3자리 + `*` 7개로만 읽는다 — 평문이 애플리케이션 메모리로 오지 않는다(D-MD-008, `BusinessRegistrationNumber.masked()`와 같은 모양).
 */
internal object MasterSqlText {
    private const val VISIBLE_DIGITS = 3
    private const val MASK = "*******"

    fun instant(field: Field<LocalDateTime?>): Field<String?> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    fun maskedRegistrationNumber(): Field<String> = DSL.concat(DSL.left(BUSINESS.REGISTRATION_NUMBER, VISIBLE_DIGITS), DSL.inline(MASK))
}
