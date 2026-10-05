// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooq.ok.sample.adapter.query

import org.jooq.DSLContext
import com.paycore.archfixture.jooq.ok.generated.OrderTable

class OrderQueryAdapter(private val dsl: DSLContext) {
    fun tableName(): String = OrderTable.NAME.last() + dsl.dialect().name
}
