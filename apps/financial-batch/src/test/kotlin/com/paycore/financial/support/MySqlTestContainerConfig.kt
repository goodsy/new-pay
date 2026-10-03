package com.paycore.financial.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 실제 MySQL 8.4 LTS 컨테이너(FINANCIAL DB 역할). 운영 DB/키에 연결하지 않는다.
 * payment-api의 동일 클래스와 중복이다. 공통 test-support 추출은 FOUNDATION-001에서 한다.
 */
@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))

    companion object {
        const val MYSQL_IMAGE = "mysql:8.4.11"
    }
}
