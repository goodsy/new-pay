package com.paycore.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 실제 MySQL 8.4 LTS 컨테이너(app마다 자기 소유 DB 역할: payment-api=PAYMENT, financial-batch=FINANCIAL).
 * 운영 DB/키에 연결하지 않는다. 같은 설정을 쓰는 spec은 Spring 테스트 컨텍스트와 컨테이너를 공유한다.
 *
 * 사용하는 app은 spring-boot-testcontainers·testcontainers-mysql을 직접 testImplementation으로 선언해야 한다
 * (이 라이브러리는 compileOnly로만 참조한다).
 */
@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))

    companion object {
        /** MySQL 이미지 tag의 저장소 단일 기준. payment-api jOOQ 생성기도 이 상수를 쓴다. */
        const val MYSQL_IMAGE = "mysql:8.4.11"
    }
}
