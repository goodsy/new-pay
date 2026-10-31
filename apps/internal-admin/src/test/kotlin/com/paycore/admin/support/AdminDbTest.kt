package com.paycore.admin.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.mysql.MySQLContainer

/**
 * internal-admin Spring 시험 공통 DB 구성(ADMIN-BOOT-001 S2 — ADMIN DB 소유, D-ADMIN-005). `@ServiceConnection`(컨테이너 기본 계정)을
 * 쓰지 않고 운영과 같은 환경변수 이름(PAYCORE_ADMIN_DB_*)으로 application.yml 바인딩 그대로 `paycore_admin`에 붙는다:
 * 앱 pool = runtime 계정(role의 표·열 단위 권한만), Flyway = migration 계정.
 */
@TestConfiguration(proxyBeanMethods = false)
class AdminDbTestConfig {

    @Bean
    fun adminMySqlContainer(): MySQLContainer = AdminDbAccounts.startedContainer()

    @Bean
    fun adminDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        AdminDbAccounts.applicationProperties(container).forEach { (name, value) -> registry.add(name) { value } }
    }
}

/** ADMIN DB가 필요한 `@SpringBootTest`에 붙인다(webEnvironment는 각 spec이 정한다). */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Import(AdminDbTestConfig::class)
annotation class AdminDbTest
