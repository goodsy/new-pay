package com.paycore.master

import com.paycore.testsupport.MySqlTestContainerConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.test.context.DynamicPropertyRegistrar
import org.springframework.test.context.TestPropertySource
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * master-api DB 시험 공통 구성(한 Spring 컨텍스트·한 MySQL 컨테이너를 공유한다).
 *
 * - 컨테이너: 저장소 단일 이미지 기준([MySqlTestContainerConfig.MYSQL_IMAGE], 8.4.11)에 **로컬 개발용 초기화 스크립트
 *   (docker/mysql/init/01-owned-databases.sh)를 그대로** 넣어 MASTER/PAYMENT/FINANCIAL DB와 계정을 만든다. 따라서 이 시험은 스크립트
 *   사본이 아니라 실제 compose가 쓰는 파일의 권한 결과를 본다.
 * - 앱 연결: `@ServiceConnection`(컨테이너 기본 `test` 계정·`test` DB)을 쓰지 않는다. 운영과 같은 환경변수 이름
 *   (PAYCORE_MASTER_DB_*)을 [DynamicPropertyRegistrar]로 넣어 application.yml의 바인딩 그대로 MASTER DB(`paycore_master`)에만
 *   붙는다. runtime 계정은 DML만, Flyway는 migration 계정을 쓴다(CP-DB-001 계정 분리 결정).
 * - 컨테이너 OS 시간대를 Asia/Seoul(+09:00)로 둔다. MySQL 세션 기본값(SYSTEM)이 UTC가 아니어야 UTC 세션 설정 시험이 의미가 있다.
 * - 비밀번호는 JVM마다 임의 생성한 일회용 값이다. 운영 계정/키가 아니다.
 */
@TestConfiguration(proxyBeanMethods = false)
class MasterDbTestConfig {

    @Bean
    fun masterMySqlContainer(): MySQLContainer = MasterDbAccounts.newContainer()

    @Bean
    fun masterDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("PAYCORE_MASTER_DB_URL") { MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE) }
        registry.add("PAYCORE_MASTER_DB_USERNAME") { MasterDbAccounts.MASTER_RUNTIME_USER }
        registry.add("PAYCORE_MASTER_DB_PASSWORD") { MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_RUNTIME_USER) }
        registry.add("PAYCORE_MASTER_DB_MIGRATION_USERNAME") { MasterDbAccounts.MASTER_MIGRATION_USER }
        registry.add("PAYCORE_MASTER_DB_MIGRATION_PASSWORD") {
            MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER)
        }
    }
}

/**
 * master-api의 DB 시험(실제 MySQL 8.4.11). 운영 migration 위치에 더해 시험 전용 migration(db/testmigration/master: 기술 probe 테이블)을
 * 적용한다. 모든 DB spec이 같은 속성 조합을 써서 컨텍스트·컨테이너를 하나만 만든다(메모리 상한, MASTER-BOOT-001).
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(MasterDbTestConfig::class)
@TestPropertySource(properties = ["spring.flyway.locations=${MasterDbAccounts.MAIN_MIGRATIONS},${MasterDbAccounts.TEST_MIGRATIONS}"])
annotation class MasterDbTest

object MasterDbAccounts {
    const val MASTER_DATABASE = "paycore_master"
    const val PAYMENT_DATABASE = "paycore_payment"
    const val FINANCIAL_DATABASE = "paycore_financial"

    const val MASTER_RUNTIME_USER = "paycore_master"
    const val MASTER_MIGRATION_USER = "paycore_master_migration"
    const val PAYMENT_RUNTIME_USER = "paycore_payment"
    const val PAYMENT_MIGRATION_USER = "paycore_payment_migration"
    const val FINANCIAL_USER = "paycore_financial"

    const val MAIN_MIGRATIONS = "classpath:db/migration/master"
    const val TEST_MIGRATIONS = "classpath:db/testmigration/master"

    /** 컨테이너 OS 시간대(UTC가 아님). */
    const val CONTAINER_TIME_ZONE = "Asia/Seoul"

    /** 계정 → 일회용 비밀번호(JVM마다 새로 생성). */
    val passwords: Map<String, String> = listOf(
        MASTER_RUNTIME_USER, MASTER_MIGRATION_USER, PAYMENT_RUNTIME_USER, PAYMENT_MIGRATION_USER, FINANCIAL_USER,
    ).associateWith { UUID.randomUUID().toString().replace("-", "") }

    fun newContainer(): MySQLContainer {
        val initScript = File(requireNotNull(System.getProperty("paycore.mysqlInitDir")) { "paycore.mysqlInitDir not set" })
            .resolve("01-owned-databases.sh")
        require(initScript.isFile) { "init script not found: $initScript" }
        return MySQLContainer(DockerImageName.parse(MySqlTestContainerConfig.MYSQL_IMAGE))
            .withEnv("TZ", CONTAINER_TIME_ZONE)
            .withEnv("PAYCORE_MASTER_DB_PASSWORD", passwords.getValue(MASTER_RUNTIME_USER))
            .withEnv("PAYCORE_MASTER_DB_MIGRATION_PASSWORD", passwords.getValue(MASTER_MIGRATION_USER))
            .withEnv("PAYCORE_PAYMENT_DB_PASSWORD", passwords.getValue(PAYMENT_RUNTIME_USER))
            .withEnv("PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD", passwords.getValue(PAYMENT_MIGRATION_USER))
            .withEnv("PAYCORE_FINANCIAL_DB_PASSWORD", passwords.getValue(FINANCIAL_USER))
            .withCopyFileToContainer(
                MountableFile.forHostPath(initScript.absolutePath),
                "/docker-entrypoint-initdb.d/01-owned-databases.sh",
            )
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

    fun connect(container: MySQLContainer, database: String, user: String): Connection =
        DriverManager.getConnection(urlFor(container, database), user, passwords.getValue(user))

    /** 권한 조회(SHOW GRANTS) 전용. Testcontainers는 root 비밀번호를 컨테이너 비밀번호와 같게 둔다. 일회용 컨테이너 값이다. */
    fun root(container: MySQLContainer): Connection =
        DriverManager.getConnection(urlFor(container, MASTER_DATABASE), "root", container.password)
}

/** 단일 값 조회(없으면 null). */
fun Connection.scalar(sql: String, vararg args: Any?): Any? =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
    }

fun Connection.column(sql: String): List<String> =
    prepareStatement(sql).use { ps -> ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }

/** 실행하고 MySQL vendor 오류 코드(성공이면 null)를 돌려준다. */
fun Connection.errorCodeOf(sql: String): Int? =
    try {
        createStatement().use { it.execute(sql) }
        null
    } catch (e: java.sql.SQLException) {
        e.errorCode
    }
