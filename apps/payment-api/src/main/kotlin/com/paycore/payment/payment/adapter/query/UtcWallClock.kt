package com.paycore.payment.payment.adapter.query

import org.jooq.Field
import org.jooq.impl.DSL
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * jOOQ 조회의 시각 규칙(ARCHITECTURE-CONVENTIONS §9.1 — MASTER `JooqMerchantStructureReader`와 같다). 저장 값은 UTC 벽시계 `DATETIME(3)`인데 jOOQ 생성 필드
 * (`LocalDateTime`)는 driver가 UTC 순간으로 해석한 뒤 **JVM 기본 시간대**로 만든다 — Asia/Seoul JVM에서 9시간 어긋났다(RED: `CheckoutWebSpec` 만료 직후 화면 200,
 * 만료 후보 0건). 그래서 읽기는 SQL `DATE_FORMAT` ISO 문자열 → `Instant.parse`, 비교 값은 `CAST('yyyy-MM-dd HH:mm:ss.SSS' AS DATETIME(3))` 리터럴로 묶는다.
 */
internal object UtcWallClock {
    private val LITERAL: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneOffset.UTC)

    /** 저장 UTC 벽시계를 ISO-8601 `…Z` 문자열로 읽는 필드. */
    fun iso(field: Field<*>): Field<String> = DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)

    /** 비교용 UTC 벽시계 리터럴(바인딩하지 않는다 — 시간대 변환 없음). */
    fun literal(at: Instant): Field<java.time.LocalDateTime> =
        DSL.field("CAST({0} AS DATETIME(3))", java.time.LocalDateTime::class.java, DSL.inline(LITERAL.format(at)))

    fun parse(iso: String?): Instant? = iso?.let(Instant::parse)
}
