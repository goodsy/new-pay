package com.paycore.admin.archfixture.adapter.batch

import org.springframework.jdbc.core.JdbcTemplate

/** ArchUnit 위반 fixture(F-1, 시험 소스 전용 — bean 아님): 1회성 명령 runner(adapter.batch)가 JdbcTemplate으로 직접 DB를 바꾼다. */
@Suppress("unused")
class JdbcTemplateInBatchRunnerFixture(private val jdbcTemplate: JdbcTemplate) {
    fun run(): Int = jdbcTemplate.update("UPDATE admin_account SET status = 'ACTIVE'")
}
