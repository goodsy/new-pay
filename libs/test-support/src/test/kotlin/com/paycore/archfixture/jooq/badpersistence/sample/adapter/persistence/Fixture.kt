// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.jooq.badpersistence.sample.adapter.persistence

import org.jooq.DSLContext

class JooqWriterInPersistence(private val dsl: DSLContext) {
    fun dialectName(): String = dsl.dialect().name
}
