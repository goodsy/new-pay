// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooqdml.ok.sample.adapter.query

import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Result
import org.jooq.impl.DSL

/** 조회만 하는 query adapter(select/fetch/count). */
class OrderViewQuery(private val dsl: DSLContext) {
    fun count(): Int = dsl.fetchCount(DSL.table("order_view"))

    fun rows(): Result<Record> = dsl.selectFrom(DSL.table("order_view")).where(DSL.field("mid").eq("demo")).fetch()
}
