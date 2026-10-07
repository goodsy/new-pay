// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooqdml.badraw.sample.adapter.query

import org.jooq.DSLContext

class RawSqlQuery(private val dsl: DSLContext) {
    fun write(): Int = dsl.execute("DELETE FROM orders")
}
