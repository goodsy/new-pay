// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.ok.sample.adapter.query

import org.jooq.DSLContext

/** jOOQ 조회 adapter는 Boot가 구성한 DSLContext만 주입받는다(DataSource/java.sql 직접 사용 불필요). */
class OrderViewQuery(private val dsl: DSLContext) {
    fun one(): Int = dsl.selectOne().fetchOne(0, Int::class.java) ?: 0
}
