package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.payment.application.required.OrderPaymentControlRepository
import com.paycore.payment.payment.application.required.PaymentRepository
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.transaction.PlatformTransactionManager
import org.testcontainers.mysql.MySQLContainer

/** [PaymentDbTest] 구성: 원시 JDBC 연결([DbAccess]), 사건 보류 시험 준비([IncidentReporter]). Port 장애 주입 대역은 [FaultInjectionConfig]. */
@TestConfiguration(proxyBeanMethods = false)
class PaymentDbTestConfig {

    @Bean
    fun dbAccess(container: MySQLContainer): DbAccess = DbAccess(container)

    @Bean
    fun incidentReporter(transactionManager: PlatformTransactionManager, controls: OrderPaymentControlRepository, payments: PaymentRepository): IncidentReporter =
        IncidentReporter(transactionManager, controls, payments)
}
