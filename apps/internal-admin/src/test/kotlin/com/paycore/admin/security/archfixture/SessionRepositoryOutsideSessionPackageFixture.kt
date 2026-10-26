package com.paycore.admin.security.archfixture

import org.springframework.session.jdbc.JdbcIndexedSessionRepository

/** ArchUnit 위반 fixture(F-1, 시험 소스 전용 — bean 아님): security.session 밖에서 Spring Session JDBC 타입을 쓴다. */
@Suppress("unused")
class SessionRepositoryOutsideSessionPackageFixture(private val repository: JdbcIndexedSessionRepository) {
    fun rename() = repository.setTableName("admin_session")
}
