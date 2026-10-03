package com.paycore.payment.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/** 실제 MySQL 8.4 LTS 컨테이너. 운영 DB/키에 연결하지 않는다. */
@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))

    companion object {
        const val MYSQL_IMAGE = "mysql:8.4.11"
    }
}
