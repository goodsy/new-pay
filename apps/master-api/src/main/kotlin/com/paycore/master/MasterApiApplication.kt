package com.paycore.master

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * master-api 실행 단위. MASTER DB만 소유한다(D-CPC-006). PAYMENT/FINANCIAL Credential을 주입하지 않는다.
 * 하위 패키지가 Spring Modulith application module이 된다. 업무 모듈(Business·Merchant·MID 등)은 MASTER-DOMAIN-001부터 추가한다.
 */
@SpringBootApplication
class MasterApiApplication

fun main(args: Array<String>) {
    runApplication<MasterApiApplication>(*args)
}
