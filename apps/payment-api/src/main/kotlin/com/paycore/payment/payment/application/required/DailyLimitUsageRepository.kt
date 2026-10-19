package com.paycore.payment.payment.application.required

import com.paycore.payment.payment.domain.DailyLimitUsage
import com.paycore.payment.payment.domain.LimitUsageKey
import java.time.Instant

/**
 * 일 한도 사용량 Aggregate 저장 Port(AUTH-001 명세 §7.3, AUTH-CONTRACT-001 §4.5). 키 = 환경 × MID × 통화 × Asia/Seoul 업무일. 권위 상태는 이 행이며 캐시·Redis가 대신하지 않는다.
 * 서로 다른 승인 사이 합계 불변식은 이 행의 비관적 잠금으로 직렬화한다. TX 없이 호출하면 거부한다(MANDATORY).
 */
interface DailyLimitUsageRepository {
    /** 행이 없으면 만들고(insert-if-absent — 동시 생성 경쟁도 UNIQUE 충돌 없이 한 행으로 수렴) 잠가 읽는다(`FOR UPDATE`). */
    fun lockOrCreate(key: LimitUsageKey, at: Instant): Versioned<DailyLimitUsage>

    /** 점유 추가·상태 변경을 반영한다(점유 행은 지우지 않는다). 새 version을 돌려준다. */
    fun update(usage: DailyLimitUsage, expectedVersion: Long, at: Instant): Long
}
