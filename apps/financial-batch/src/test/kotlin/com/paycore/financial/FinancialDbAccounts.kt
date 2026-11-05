package com.paycore.financial

import com.paycore.testsupport.MySqlTestContainerConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.UUID

/**
 * financial-batch의 모든 DB 시험 컨텍스트가 쓰는 MySQL 구성(FIN-DBACCOUNT-001 — payment-api PaymentMySqlContainerConfig와 같은 방식).
 *
 * - 컨테이너: 저장소 단일 이미지([MySqlTestContainerConfig.MYSQL_IMAGE], 8.4.11)에 **로컬 개발용 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)를
 *   그대로** 넣는다. 시험은 운영과 같은 계정 구조에서 돈다: runtime 계정 `paycore_financial`(role `paycore_financial_runtime`만 — 표·열 단위 권한),
 *   migration 계정 `paycore_financial_migration`(Flyway), 권한 부여 procedure. `@ServiceConnection`(컨테이너 기본 `test` 계정, DB ALL)은 쓰지 않는다 —
 *   그 계정으로는 append-only 표 권한이 실제로 지켜지는지 보이지 않는다.
 * - 앱 연결: 운영과 같은 환경변수 이름(PAYCORE_FINANCIAL_DB_*)을 [DynamicPropertyRegistrar]로 넣어 application.yml 바인딩 그대로 붙는다.
 *   업무 시험(Inbox·투영·Batch 재시작) 전체가 runtime 계정 권한으로 실행된다(정상 경로가 권한 안에서 통과한다는 증거).
 * - 컨테이너 OS 시간대 Asia/Seoul(+09:00): 세션 UTC 설정이 실제로 적용되는지 보이게 한다(payment-api와 같다).
 * - 비밀번호는 JVM마다 임의 생성한 일회용 값이다. 운영 계정/키가 아니다. ADMIN 비밀번호는 넣지 않는다(새 인스턴스 — 스크립트가 ADMIN을 건너뛴다).
 */
@TestConfiguration(proxyBeanMethods = false)
class FinancialMySqlContainerConfig {

    @Bean
    fun financialMySqlContainer(): MySQLContainer = FinancialDbAccounts.newContainer().also { it.start() }

    @Bean
    fun financialDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("PAYCORE_FINANCIAL_DB_URL") { FinancialDbAccounts.urlFor(container, FinancialDbAccounts.FINANCIAL_DATABASE) }
        registry.add("PAYCORE_FINANCIAL_DB_USERNAME") { FinancialDbAccounts.RUNTIME_USER }
        registry.add("PAYCORE_FINANCIAL_DB_PASSWORD") { FinancialDbAccounts.password(FinancialDbAccounts.RUNTIME_USER) }
        registry.add("PAYCORE_FINANCIAL_DB_MIGRATION_USERNAME") { FinancialDbAccounts.MIGRATION_USER }
        registry.add("PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD") { FinancialDbAccounts.password(FinancialDbAccounts.MIGRATION_USER) }
    }
}

object FinancialDbAccounts {
    const val FINANCIAL_DATABASE = "paycore_financial"
    const val MASTER_DATABASE = "paycore_master"
    const val PAYMENT_DATABASE = "paycore_payment"

    const val RUNTIME_USER = "paycore_financial"
    const val MIGRATION_USER = "paycore_financial_migration"
    const val RUNTIME_ROLE = "paycore_financial_runtime"
    const val GRANTOR = "paycore_financial_grantor"
    const val MASTER_RUNTIME_USER = "paycore_master"
    const val MASTER_MIGRATION_USER = "paycore_master_migration"
    const val PAYMENT_RUNTIME_USER = "paycore_payment"
    const val PAYMENT_MIGRATION_USER = "paycore_payment_migration"

    const val MAIN_MIGRATIONS = "classpath:db/migration/financial"
    const val RUNTIME_PRIVILEGES = "classpath:db/runtime-privilege/financial"
    const val TEST_MIGRATIONS = "classpath:db/testmigration/financial"

    /** 시험 전용 표(tech_smoke_batch_out)의 runtime 권한 migration(schema migration과 다른 위치 — master-api db/testprivilege 관례). */
    const val TEST_PRIVILEGES = "classpath:db/testprivilege/financial"

    const val INIT_SCRIPT_IN_CONTAINER = "/docker-entrypoint-initdb.d/01-owned-databases.sh"

    /** 컨테이너 OS 시간대(UTC가 아님). */
    const val CONTAINER_TIME_ZONE = "Asia/Seoul"

    /** 계정 → 일회용 비밀번호(JVM마다 새로 생성). */
    private val passwords: Map<String, String> = listOf(
        RUNTIME_USER, MIGRATION_USER, MASTER_RUNTIME_USER, MASTER_MIGRATION_USER, PAYMENT_RUNTIME_USER, PAYMENT_MIGRATION_USER,
    ).associateWith { UUID.randomUUID().toString().replace("-", "") }

    fun password(user: String): String = passwords.getValue(user)

    fun newContainer(): MySQLContainer {
        val initScript = File(requireNotNull(System.getProperty("paycore.mysqlInitDir")) { "paycore.mysqlInitDir not set" })
            .resolve("01-owned-databases.sh")
        require(initScript.isFile) { "init script not found: $initScript" }
        return MySqlTestContainerConfig.newTestMySqlContainer()
            .withEnv("TZ", CONTAINER_TIME_ZONE)
            .withEnv("PAYCORE_MASTER_DB_PASSWORD", password(MASTER_RUNTIME_USER))
            .withEnv("PAYCORE_MASTER_DB_MIGRATION_PASSWORD", password(MASTER_MIGRATION_USER))
            .withEnv("PAYCORE_PAYMENT_DB_PASSWORD", password(PAYMENT_RUNTIME_USER))
            .withEnv("PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD", password(PAYMENT_MIGRATION_USER))
            .withEnv("PAYCORE_FINANCIAL_DB_PASSWORD", password(RUNTIME_USER))
            .withEnv("PAYCORE_FINANCIAL_DB_MIGRATION_PASSWORD", password(MIGRATION_USER))
            .withCopyFileToContainer(MountableFile.forHostPath(initScript.absolutePath, 0b111_101_101), INIT_SCRIPT_IN_CONTAINER)
    }

    /** 컨테이너 JDBC URL의 DB 이름만 바꾼다. 새 계정(caching_sha2_password)이 비SSL로 접속할 수 있게 공개키 조회를 허용한다. */
    fun urlFor(container: MySQLContainer, database: String): String {
        val url = container.jdbcUrl
        val base = url.substringBefore('?').substringBeforeLast('/') + "/" + database
        val params = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toMutableList()
        if (params.none { it.startsWith("allowPublicKeyRetrieval=") }) params += "allowPublicKeyRetrieval=true"
        if (params.none { it.startsWith("useSSL=") }) params += "useSSL=false"
        return base + "?" + params.joinToString("&")
    }

    fun connect(container: MySQLContainer, user: String, database: String = FINANCIAL_DATABASE): Connection =
        DriverManager.getConnection(urlFor(container, database), user, password(user))

    /** 관찰·권한 조회 전용 root 연결(Testcontainers는 root 비밀번호를 컨테이너 비밀번호와 같게 둔다 — 일회용 값). 기본 DB는 FINANCIAL. */
    fun root(container: MySQLContainer): Connection =
        DriverManager.getConnection(urlFor(container, FINANCIAL_DATABASE), "root", container.password)
}

/** 문장 하나를 실행하고 MySQL vendor 오류 코드(성공이면 null)를 돌려준다(SELECT·CALL·USE·DDL 모두 execute). */
fun Connection.codeOf(sql: String): Int? =
    try {
        createStatement().use { it.execute(sql) }
        null
    } catch (e: SQLException) {
        e.errorCode
    }

/** 단일 값 조회(없으면 null). */
fun Connection.scalar(sql: String, vararg args: Any?): Any? =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
    }

/** 첫 열 목록 조회. */
fun Connection.column(sql: String, vararg args: Any?): List<String> =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }
