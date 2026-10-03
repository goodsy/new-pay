package com.paycore.payment

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * payment-api 실행 단위. PAYMENT DB만 소유한다.
 * 하위 패키지가 Spring Modulith application module이 된다(FOUNDATION-001에서 구성).
 */
@SpringBootApplication
class PaymentApiApplication

fun main(args: Array<String>) {
    runApplication<PaymentApiApplication>(*args)
}
