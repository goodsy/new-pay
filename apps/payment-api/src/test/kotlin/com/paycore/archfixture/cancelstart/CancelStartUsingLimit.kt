package com.paycore.archfixture.cancelstart

import com.paycore.payment.payment.domain.DailyLimitUsage

/**
 * ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): 취소 시작 입력이 일 한도 사용량에 의존한다(취소는 일 한도·신규 승인 권리와 무관 — K-3·D-AUTH-007,
 * review 편차 3). 대상 = DailyLimitUsage 의존.
 */
class CancelStartUsingLimit(val usage: DailyLimitUsage)
