package com.paycore.admin.support

import com.paycore.testsupport.MySqlTestContainerConfig
import com.paycore.testsupport.containers.PrefetchedStarts
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.MountableFile
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID

/**
 * internal-admin DB 시험의 계정·컨테이너(ADMIN-BOOT-001 S2). master-api `MasterDbAccounts`와 같은 방식:
 * 저장소 단일 이미지(8.4.11)에 **로컬 개발용 초기화 스크립트(docker/mysql/init/01-owned-databases.sh)를 그대로** 넣어 계정·role·procedure를 만든다.
 * 비밀번호는 JVM마다 임의 생성한 일회용 값이다(운영 계정/키 아님). 컨테이너 OS 시간대는 UTC가 아니게(Asia/Seoul) 둔다.
 */
object AdminDbAccounts {
    const val ADMIN_DATABASE = "paycore_admin"
    const val MASTER_DATABASE = "paycore_master"
    const val PAYMENT_DATABASE = "paycore_payment"
    const val FINANCIAL_DATABASE = "paycore_financial"

    const val ADMIN_RUNTIME_USER = "paycore_admin"
    const val ADMIN_RUNTIME_ROLE = "paycore_admin_runtime"
    const val ADMIN_GRANTOR = "paycore_admin_grantor"
    const val ADMIN_MIGRATION_USER = "paycore_admin_migration"
    const val MASTER_RUNTIME_USER = "paycore_master"
    const val MASTER_MIGRATION_USER = "paycore_master_migration"
    const val PAYMENT_RUNTIME_USER = "paycore_payment"
    const val PAYMENT_MIGRATION_USER = "paycore_payment_migration"
    const val FINANCIAL_USER = "paycore_financial"

    const val MAIN_MIGRATIONS = "classpath:db/migration/admin"
    const val RUNTIME_PRIVILEGES = "classpath:db/runtime-privilege/admin"
    const val CONTAINER_TIME_ZONE = "Asia/Seoul"
    const val INIT_SCRIPT_IN_CONTAINER = "/docker-entrypoint-initdb.d/01-owned-databases.sh"

    val passwords: Map<String, String> = listOf(
        ADMIN_RUNTIME_USER, ADMIN_MIGRATION_USER, MASTER_RUNTIME_USER, MASTER_MIGRATION_USER, PAYMENT_RUNTIME_USER,
        PAYMENT_MIGRATION_USER, FINANCIAL_USER,
    ).associateWith { UUID.randomUUID().toString().replace("-", "") }

    /** 초기화 스크립트가 읽는 환경변수 이름 → 값. */
    val initEnvironment: Map<String, String> = linkedMapOf(
        "PAYCORE_MASTER_DB_PASSWORD" to passwords.getValue(MASTER_RUNTIME_USER),
        "PAYCORE_MASTER_DB_MIGRATION_PASSWORD" to passwords.getValue(MASTER_MIGRATION_USER),
        "PAYCORE_PAYMENT_DB_PASSWORD" to passwords.getValue(PAYMENT_RUNTIME_USER),
        "PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD" to passwords.getValue(PAYMENT_MIGRATION_USER),
        "PAYCORE_FINANCIAL_DB_PASSWORD" to passwords.getValue(FINANCIAL_USER),
        "PAYCORE_ADMIN_DB_PASSWORD" to passwords.getValue(ADMIN_RUNTIME_USER),
        "PAYCORE_ADMIN_DB_MIGRATION_PASSWORD" to passwords.getValue(ADMIN_MIGRATION_USER),
    )

    fun initScript(): File {
        val script = File(requireNotNull(System.getProperty("paycore.mysqlInitDir")) { "paycore.mysqlInitDir not set" })
            .resolve("01-owned-databases.sh")
        require(script.isFile) { "init script not found: $script" }
        return script
    }

    /** ADMIN 비밀번호를 넘기지 않으면 다른 모듈(master-api·payment-api)의 시험 컨테이너와 같은 조건이 된다(D-55 — ADMIN 건너뜀). */
    fun newContainer(withAdmin: Boolean = true): MySQLContainer {
        val container = MySqlTestContainerConfig.newTestMySqlContainer().withEnv("TZ", CONTAINER_TIME_ZONE)
        initEnvironment.filterKeys { withAdmin || !it.startsWith("PAYCORE_ADMIN_") }.forEach { (name, value) -> container.withEnv(name, value) }
        return container.withCopyFileToContainer(MountableFile.forHostPath(initScript().absolutePath), INIT_SCRIPT_IN_CONTAINER)
    }

    /**
     * [newContainer]`()`(기본 설정)와 같은 설정의 **새** 컨테이너를 시작된 상태로 돌려준다(TEST-PERF-001). 다음 컨테이너는 앞 시험이 도는 동안
     * 백그라운드에서 미리 시작해 둔다 — 재사용이 아니고 시작 시점만 앞당긴다(PrefetchedStarts). 시작된 뒤에는 설정을 바꿀 수 없으므로
     * 설정을 바꾸는 시험(예: withAdmin = false)은 [newContainer]를 직접 쓴다.
     */
    fun startedContainer(): MySQLContainer = prefetched.take()

    private val prefetched: PrefetchedStarts<MySQLContainer> by lazy {
        PrefetchedStarts.closingAtExit(
            PrefetchedStarts(
                name = "admin-mysql", create = { newContainer() }, start = { it.start() }, stop = { it.stop() },
                describe = { "${it.dockerImageName} ${it.containerId}" },
            ),
        )
    }

    /** application.yml이 읽는 운영과 같은 환경변수 이름(PAYCORE_ADMIN_DB_*). */
    fun applicationProperties(container: MySQLContainer): Map<String, String> = linkedMapOf(
        "PAYCORE_ADMIN_DB_URL" to urlFor(container, ADMIN_DATABASE),
        "PAYCORE_ADMIN_DB_USERNAME" to ADMIN_RUNTIME_USER,
        "PAYCORE_ADMIN_DB_PASSWORD" to passwords.getValue(ADMIN_RUNTIME_USER),
        "PAYCORE_ADMIN_DB_MIGRATION_USERNAME" to ADMIN_MIGRATION_USER,
        "PAYCORE_ADMIN_DB_MIGRATION_PASSWORD" to passwords.getValue(ADMIN_MIGRATION_USER),
    )

    /** 컨테이너 JDBC URL의 DB 이름만 바꾼다. 새 계정(caching_sha2_password)이 비SSL로 접속할 수 있게 공개키 조회를 허용한다. */
    fun urlFor(container: MySQLContainer, database: String): String {
        val url = container.jdbcUrl
        val base = url.substringBefore('?').substringBeforeLast('/') + "/" + database
        val params = url.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.toMutableList()
        if (params.none { it.startsWith("allowPublicKeyRetrieval=") }) params += "allowPublicKeyRetrieval=true"
        if (params.none { it.startsWith("useSSL=") }) params += "useSSL=false"
        return base + "?" + params.joinToString("&")
    }

    fun connect(container: MySQLContainer, user: String, database: String = ADMIN_DATABASE): Connection =
        DriverManager.getConnection(urlFor(container, database), user, passwords.getValue(user))

    /** 권한 조회(SHOW GRANTS) 전용 root. Testcontainers는 root 비밀번호를 컨테이너 비밀번호와 같게 둔다(일회용 값). */
    fun root(container: MySQLContainer, database: String = ADMIN_DATABASE): Connection =
        DriverManager.getConnection(urlFor(container, database), "root", container.password)
}

fun Connection.scalar(sql: String, vararg args: Any?): Any? =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
    }

fun Connection.column(sql: String, vararg args: Any?): List<String> =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

fun Connection.update(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }

/** 실행하고 MySQL vendor 오류 코드(성공이면 null)를 돌려준다. */
fun Connection.errorCodeOf(sql: String, vararg args: Any?): Int? =
    try {
        prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.execute()
        }
        null
    } catch (e: java.sql.SQLException) {
        e.errorCode
    }
