// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badjooq.sample.application

import org.jooq.DSLContext

class JooqService(private val dsl: DSLContext) {
    fun one(): Int = dsl.selectOne().fetchOne(0, Int::class.java) ?: 0
}
