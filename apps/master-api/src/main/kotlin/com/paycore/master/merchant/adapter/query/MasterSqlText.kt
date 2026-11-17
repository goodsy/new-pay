package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.MERCHANT_CODE_ASSIGNMENT
import org.jooq.Field
import org.jooq.impl.DSL
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 조회 adapter 공용 SQL 표현(MERCHANT-OPS-001). 시각은 저장 UTC 벽시계를 ISO `…Z` 문자열로 읽는다(JVM·driver 시간대 변환 없음 — JooqMerchantStructureReader KDoc,
 * TZ-FIX-001). 사업자등록번호는 SQL에서 앞 3자리 + `*` 7개로만 읽는다 — 평문이 애플리케이션 메모리로 오지 않는다(D-MD-008, `BusinessRegistrationNumber.masked()`와 같은 모양).
 */
internal object MasterSqlText {
    /** MERCHANT-CODE-001 §3.2·§3.8: 현재 가맹점 코드 = 가장 큰 assignment_seq 행의 코드(PK (merchant_id, seq) 범위 읽기 — 상관 부분 질의). */
    fun currentMerchantCode(merchantId: Field<String?>): Field<String?> = DSL.field(
        DSL.select(MERCHANT_CODE_ASSIGNMENT.MERCHANT_CODE)
            .from(MERCHANT_CODE_ASSIGNMENT)
            .where(MERCHANT_CODE_ASSIGNMENT.MERCHANT_ID.eq(merchantId))
            .orderBy(MERCHANT_CODE_ASSIGNMENT.ASSIGNMENT_SEQ.desc())
            .limit(1),
    )

    private const val VISIBLE_DIGITS = 3
    private const val MASK = "*******"

    fun instant(field: Field<LocalDateTime?>): Field<String?> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    fun maskedRegistrationNumber(): Field<String> = DSL.concat(DSL.left(BUSINESS.REGISTRATION_NUMBER, VISIBLE_DIGITS), DSL.inline(MASK))

    /**
     * BUSINESS-PROFILE-001 §6.2·§9: 사람 이름 마스킹을 SQL에서(원문이 메모리로 오지 않는다) — 첫 code point + 나머지 `*`(1글자면 `김*`). Domain `BusinessPiiMask.name`과 같은 모양
     * (utf8mb4 `LEFT`·`CHAR_LENGTH`는 문자(code point) 단위 — BusinessReaderSpec이 두 결과를 대조한다).
     */
    fun maskedPersonName(name: Field<String?>): Field<String?> =
        DSL.field("CONCAT(LEFT({0}, 1), REPEAT('*', GREATEST(1, CHAR_LENGTH({0}) - 1)))", String::class.java, name)

    /** UTC 순간 비교 값: `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))` 리터럴(LocalDateTime 바인딩은 JVM 시간대로 해석된다 — JooqEntitlementPropagationReader 관례). */
    fun utcLiteral(at: Instant): Field<LocalDateTime?> = DSL.field("CAST({0} AS DATETIME(3))", LocalDateTime::class.java, DSL.inline(UTC_WALL_CLOCK.format(at)))

    private val UTC_WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)
}
