package com.paycore.payment.payment.adapter.persistence

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer

/** [PaymentDbTest] 구성: 원시 JDBC 연결([DbAccess]). Port 장애 주입 대역은 [FaultInjectionConfig]. */
@TestConfiguration(proxyBeanMethods = false)
class PaymentDbTestConfig {

    @Bean
    fun dbAccess(container: MySQLContainer): DbAccess = DbAccess(container)
}
