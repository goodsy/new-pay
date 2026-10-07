// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooqdml.badupdate.sample.adapter.query

import org.jooq.DSLContext
import org.jooq.impl.DSL

class OrderUpdatingQuery(private val dsl: DSLContext) {
    fun write(): Int = dsl.update(DSL.table("orders")).set(DSL.field("status", String::class.java), "PAID").execute()
}
