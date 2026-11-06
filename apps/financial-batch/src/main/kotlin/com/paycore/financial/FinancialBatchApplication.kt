package com.paycore.financial

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

/**
 * financial-batch 실행 단위. FINANCIAL DB만 소유한다(PAYMENT/MASTER Credential을 주입하지 않는다).
 * UserDetailsServiceAutoConfiguration 제외(ACQ-VIEW-001 R-2 A — master-api와 같다): HTTP 표면은 결제 사실 Inbox(HMAC)·ADMIN 서명 채널·health뿐이라 Boot 기본 사용자
 * ("user" + 기동 시 생성 비밀번호 로그)를 만들지 않는다.
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class FinancialBatchApplication

fun main(args: Array<String>) {
    runApplication<FinancialBatchApplication>(*args)
}
