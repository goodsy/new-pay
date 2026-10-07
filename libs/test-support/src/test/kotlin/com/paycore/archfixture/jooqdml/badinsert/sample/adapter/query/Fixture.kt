// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooqdml.badinsert.sample.adapter.query

import org.jooq.DSLContext
import org.jooq.impl.DSL

class OrderInsertingQuery(private val dsl: DSLContext) {
    fun write(): Int = dsl.insertInto(DSL.table("orders")).set(DSL.field("id", Long::class.java), 1L).execute()
}
