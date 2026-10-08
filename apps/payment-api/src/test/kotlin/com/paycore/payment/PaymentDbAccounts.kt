package com.paycore.payment

import com.paycore.testsupport.MySqlTestContainerConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * payment-api의 모든 DB 시험 컨텍스트(PaymentDbTest·ContextSmoke·TechSmoke)가 쓰는 MySQL 구성(PAYMENT-DBACCOUNT-001).
 *
 * - 컨테이너: 저장소 단일 이미지 기준([MySqlTestContainerConfig.MYSQL_IMAGE], 8.4.11)에 **로컬 개발용 초기화 스크립트
 *   (docker/mysql/init/01-owned-databases.sh)를 그대로** 넣는다. 그래서 시험은 운영과 같은 계정 구조에서 돈다: runtime 계정
 *   `paycore_payment`(role `paycore_payment_runtime`만, 표 단위 권한), migration 계정 `paycore_payment_migration`(Flyway), 권한 부여 procedure.
 *   `@ServiceConnection`(컨테이너 기본 `test` 계정, DB ALL)을 쓰지 않는다 — 그 계정으로는 표 단위 권한이 실제로 지켜지는지 보이지 않는다.
 * - 앱 연결: 운영과 같은 환경변수 이름(PAYCORE_PAYMENT_DB_*)을 [DynamicPropertyRegistrar]로 넣어 application.yml 바인딩 그대로
 *   PAYMENT DB(`paycore_payment`)에 붙는다. 업무 시험 전체가 runtime 계정 권한으로 실행된다(정상 경로가 권한 안에서 통과한다는 증거).
 * - 컨테이너 OS 시간대는 Asia/Seoul(+09:00): MySQL 세션 기본값(SYSTEM)이 UTC가 아니어야 앱·Flyway 세션 UTC 설정 시험이 의미가 있다.
 * - 비밀번호는 JVM마다 임의 생성한 일회용 값이다. 운영 계정/키가 아니다.
 */
@TestConfiguration(proxyBeanMethods = false)
class PaymentMySqlContainerConfig {

    @Bean
    fun paymentMySqlContainer(): MySQLContainer = PaymentDbAccounts.newContainer()

    @Bean
    fun paymentDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("PAYCORE_PAYMENT_DB_URL") { PaymentDbAccounts.urlFor(container, PaymentDbAccounts.PAYMENT_DATABASE) }
        registry.add("PAYCORE_PAYMENT_DB_USERNAME") { PaymentDbAccounts.PAYMENT_RUNTIME_USER }
        registry.add("PAYCORE_PAYMENT_DB_PASSWORD") { PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_RUNTIME_USER) }
        registry.add("PAYCORE_PAYMENT_DB_MIGRATION_USERNAME") { PaymentDbAccounts.PAYMENT_MIGRATION_USER }
        registry.add("PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD") { PaymentDbAccounts.password(PaymentDbAccounts.PAYMENT_MIGRATION_USER) }
    }
}

object PaymentDbAccounts {
    const val PAYMENT_DATABASE = "paycore_payment"
    const val MASTER_DATABASE = "paycore_master"
    const val FINANCIAL_DATABASE = "paycore_financial"

    const val PAYMENT_RUNTIME_USER = "paycore_payment"
    const val PAYMENT_MIGRATION_USER = "paycore_payment_migration"
    const val PAYMENT_RUNTIME_ROLE = "paycore_payment_runtime"
    const val PAYMENT_GRANTOR = "paycore_payment_grantor"
    const val MASTER_RUNTIME_USER = "paycore_master"
    const val MASTER_MIGRATION_USER = "paycore_master_migration"
    const val FINANCIAL_USER = "paycore_financial"

    /** 컨테이너 OS 시간대(UTC가 아님). */
    const val CONTAINER_TIME_ZONE = "Asia/Seoul"

    /** 계정 → 일회용 비밀번호(JVM마다 새로 생성). */
    private val passwords: Map<String, String> = listOf(
        PAYMENT_RUNTIME_USER, PAYMENT_MIGRATION_USER, MASTER_RUNTIME_USER, MASTER_MIGRATION_USER, FINANCIAL_USER,
    ).associateWith { UUID.randomUUID().toString().replace("-", "") }

    fun password(user: String): String = passwords.getValue(user)

    fun newContainer(): MySQLContainer {
        val initScript = File(requireNotNull(System.getProperty("paycore.mysqlInitDir")) { "paycore.mysqlInitDir not set" })
            .resolve("01-owned-databases.sh")
        require(initScript.isFile) { "init script not found: $initScript" }
        return MySQLContainer(DockerImageName.parse(MySqlTestContainerConfig.MYSQL_IMAGE))
            .withEnv("TZ", CONTAINER_TIME_ZONE)
            .withEnv("PAYCORE_MASTER_DB_PASSWORD", password(MASTER_RUNTIME_USER))
            .withEnv("PAYCORE_MASTER_DB_MIGRATION_PASSWORD", password(MASTER_MIGRATION_USER))
            .withEnv("PAYCORE_PAYMENT_DB_PASSWORD", password(PAYMENT_RUNTIME_USER))
            .withEnv("PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD", password(PAYMENT_MIGRATION_USER))
            .withEnv("PAYCORE_FINANCIAL_DB_PASSWORD", password(FINANCIAL_USER))
            .withCopyFileToContainer(
                MountableFile.forHostPath(initScript.absolutePath, 0b111_101_101),
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
        DriverManager.getConnection(urlFor(container, database), user, password(user))

    /**
     * 관찰·권한 조회 전용 root 연결. Testcontainers는 root 비밀번호를 컨테이너 비밀번호와 같게 둔다. 일회용 컨테이너 값이다.
     * 기본 DB는 PAYMENT(접속 대상 DB를 고르지 않으면 컨테이너 기본 `test` DB가 된다).
     */
    fun root(container: MySQLContainer): Connection =
        DriverManager.getConnection(urlFor(container, PAYMENT_DATABASE), "root", container.password)
}
