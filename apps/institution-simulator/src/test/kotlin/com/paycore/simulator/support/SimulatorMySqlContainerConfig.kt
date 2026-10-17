package com.paycore.simulator.support

import com.paycore.testsupport.MySqlTestContainerConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import java.io.File
import java.util.UUID

/**
 * Simulator 시험 MySQL(AUTHN-001 §13.1, AN-19). 저장소 단일 이미지([MySqlTestContainerConfig.MYSQL_IMAGE])에 Simulator 전용 초기화 스크립트
 * (docker/simulator-mysql/init)를 그대로 넣어 운영과 같은 계정 구조(runtime = DML만, migration = DB ALL)에서 돈다. 비밀번호는 JVM마다 새로 만든 일회용 값.
 */
@TestConfiguration(proxyBeanMethods = false)
class SimulatorMySqlContainerConfig {

    @Bean
    fun simulatorMySqlContainer(): MySQLContainer = SimulatorDb.newContainer()

    @Bean
    fun simulatorDbProperties(container: MySQLContainer): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("PAYCORE_SIMULATOR_DB_URL") { SimulatorDb.url(container) }
        registry.add("PAYCORE_SIMULATOR_DB_USERNAME") { SimulatorDb.RUNTIME_USER }
        registry.add("PAYCORE_SIMULATOR_DB_PASSWORD") { SimulatorDb.password(SimulatorDb.RUNTIME_USER) }
        registry.add("PAYCORE_SIMULATOR_DB_MIGRATION_USERNAME") { SimulatorDb.MIGRATION_USER }
        registry.add("PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD") { SimulatorDb.password(SimulatorDb.MIGRATION_USER) }
    }
}

object SimulatorDb {
    const val DATABASE = "paycore_simulator"
    const val RUNTIME_USER = "paycore_simulator"
    const val MIGRATION_USER = "paycore_simulator_migration"

    private val passwords = listOf(RUNTIME_USER, MIGRATION_USER).associateWith { UUID.randomUUID().toString().replace("-", "") }

    fun password(user: String): String = passwords.getValue(user)

    fun url(container: MySQLContainer): String = "jdbc:mysql://${container.host}:${container.getMappedPort(3306)}/$DATABASE"

    fun newContainer(): MySQLContainer {
        val initScript = File(requireNotNull(System.getProperty("paycore.simulatorMysqlInitDir")) { "paycore.simulatorMysqlInitDir not set" })
            .resolve("01-simulator-database.sh")
        require(initScript.isFile) { "init script not found: $initScript" }
        return MySQLContainer(DockerImageName.parse(MySqlTestContainerConfig.MYSQL_IMAGE))
            .withEnv("PAYCORE_SIMULATOR_DB_PASSWORD", password(RUNTIME_USER))
            .withEnv("PAYCORE_SIMULATOR_DB_MIGRATION_PASSWORD", password(MIGRATION_USER))
            .withCopyFileToContainer(MountableFile.forHostPath(initScript.absolutePath, 0b111_101_101), "/docker-entrypoint-initdb.d/01-simulator-database.sh")
    }
}
