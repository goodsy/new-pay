package com.paycore.financial

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * financial-batch 실행 단위. FINANCIAL DB만 소유한다(PAYMENT/MASTER Credential을 주입하지 않는다).
 * 업무 Job은 아직 없다. Job은 Adapter로서 Application use case를 호출하도록 해당 작업에서 추가한다.
 */
@SpringBootApplication
class FinancialBatchApplication

fun main(args: Array<String>) {
    runApplication<FinancialBatchApplication>(*args)
}
