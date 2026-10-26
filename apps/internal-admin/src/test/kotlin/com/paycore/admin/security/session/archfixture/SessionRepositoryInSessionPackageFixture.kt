package com.paycore.admin.security.session.archfixture

import org.springframework.session.jdbc.JdbcIndexedSessionRepository

/**
 * ArchUnit 허용 대조 fixture(F-1, 시험 소스 전용 — bean 아님): security.session 안의 Spring Session 구성(S0 n-D — 질의 교체만, DataSource·spring-jdbc 타입 없음).
 */
@Suppress("unused")
class SessionRepositoryInSessionPackageFixture(private val repository: JdbcIndexedSessionRepository) {
    fun lowerCaseAttributeTable() = repository.setCreateSessionAttributeQuery(
        "INSERT INTO admin_session_attributes (SESSION_PRIMARY_ID, ATTRIBUTE_NAME, ATTRIBUTE_BYTES) VALUES (?, ?, ?)",
    )
}
