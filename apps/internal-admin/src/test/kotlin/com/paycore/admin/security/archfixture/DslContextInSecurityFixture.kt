package com.paycore.admin.security.archfixture

import org.jooq.DSLContext

/** ArchUnit 위반 fixture(F-1, 시험 소스 전용 — bean 아님): 공통 security 패키지가 jOOQ DSLContext로 직접 조회한다. */
@Suppress("unused")
class DslContextInSecurityFixture(private val dsl: DSLContext) {
    fun roles(): Int = dsl.fetchCount(dsl.selectOne())
}
