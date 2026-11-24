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
 * TZ-FIX-001). 사업자등록번호는 [registrationNumber] 한 곳에서 저장 원문 10자리로 읽는다(BRN-UNMASK-001 — D-BIZ-007·D-PII-001: 뉴피지 관리자 화면·조회 응답 원문).
 * 감사·변경 이력·로그·지표는 마스킹 그대로다(이력 저장값은 `BusinessRegistrationNumber.masked()` — 이 조회 표현과 무관).
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

    fun instant(field: Field<LocalDateTime?>): Field<String?> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    /**
     * BRN-UNMASK-001(D-BIZ-007·D-PII-001 ①): 관리자 조회 응답의 사업자등록번호 = 저장 원문 10자리(하이픈은 화면이 표시할 때만). 이전 SQL 마스킹(`LEFT(…, 3)` + `*******`)을 대체한다.
     * 응답 필드 `registrationNumber`(v2 계약 — 명세 §2 C-1). 값을 로그·감사·이력에 옮겨 적지 않는다(그곳은 마스킹 — D-BIZ-007).
     */
    fun registrationNumber(): Field<String> = BUSINESS.REGISTRATION_NUMBER

    /**
     * BUSINESS-PROFILE-001 §6.2·§9: 사람 이름 마스킹을 SQL에서(원문이 메모리로 오지 않는다) — 첫 code point + 나머지 `*`(1글자면 `김*`). Domain `BusinessPiiMask.name`과 같은 모양
     * (utf8mb4 `LEFT`·`CHAR_LENGTH`는 문자(code point) 단위 — BusinessReaderSpec이 두 결과를 대조한다).
     */
    fun maskedPersonName(name: Field<String?>): Field<String?> =
        DSL.field("CONCAT(LEFT({0}, 1), REPEAT('*', GREATEST(1, CHAR_LENGTH({0}) - 1)))", String::class.java, name)

    /** UTC 순간 비교 값: `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))` 리터럴(LocalDateTime 바인딩은 JVM 시간대로 해석된다 — JooqEntitlementPropagationReader 관례). */
    fun utcLiteral(at: Instant): Field<LocalDateTime?> = DSL.field("CAST({0} AS DATETIME(3))", LocalDateTime::class.java, DSL.inline(UTC_WALL_CLOCK.format(at)))

    private val UTC_WALL_CLOCK: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** LIKE 이스케이프 문자(BUSINESS-PROFILE-001 C-5 — 사업자 상호 접두와 MERCHANT-NAME-SEARCH-001 가맹점명 접두가 같이 쓴다). */
    const val ESCAPE = '!'

    /**
     * 글자 그대로의 앞부분 일치 LIKE 패턴: `!`·`%`·`_`를 [ESCAPE]로 감싼 뒤 `%`를 붙인다 — 입력이 와일드카드가 되지 않는다. `field.like(likePrefix(v), ESCAPE)`로 쓴다
     * (값은 바인드 — SQL 문자열에 섞지 않는다).
     */
    fun likePrefix(value: String): String = buildString {
        value.forEach { c ->
            if (c == ESCAPE || c == '%' || c == '_') append(ESCAPE)
            append(c)
        }
        append('%')
    }
}
