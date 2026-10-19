package com.paycore.simulator.van.domain

import java.time.Duration

/**
 * 가상 기관 Capability 프로파일(AUTH-001 §5.4·AU-15 — **Demo 설정, 운영값 아님**). [confirmsNoEffect]가 있는 프로파일(A)만 받은 적 없는 요청 ID의 무효과 종결을 확정한다.
 * [closableAfter] 전의 요청 ID는 종결하지 않고(기관 처리 지연일 수 있다), [inquiryRetention]이 지난 요청 ID의 조회는 UNAVAILABLE이다. 신용 할부 개월은 결제창 Plan Demo
 * 정책과 같은 값이어야 한다(AU-31 — PG 기동 검사). 체크카드 할부는 설정이 아니라 고정 거절이다(D-AUTH1-003).
 */
data class VanProfile(
    val name: String,
    val confirmsNoEffect: Boolean,
    val closableAfter: Duration,
    val inquiryRetention: Duration,
    val creditInstallmentMonths: Set<Int>,
    val pendingDelay: Duration,
) {
    init {
        require(name.isNotEmpty()) { "profile name must not be empty" }
        require(!closableAfter.isNegative && !pendingDelay.isNegative) { "durations must not be negative" }
        require(inquiryRetention > closableAfter) { "inquiry retention must be longer than closableAfter" }
        require(creditInstallmentMonths.all { it in 2..99 }) { "installment months must be 2..99" }
    }
}
