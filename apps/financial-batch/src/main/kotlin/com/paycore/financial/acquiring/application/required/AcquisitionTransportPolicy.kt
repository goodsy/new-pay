package com.paycore.financial.acquiring.application.required

import java.time.Duration

/**
 * 전송 운영값 Port(설정 — Demo 값, 운영값은 LIVE Gate §14-4). 마감 정책([AcquisitionPolicySource])과 나눈다: 전송은 봉인 정책에 기대지 않는다(리뷰 NIT-1).
 * [transmissionLease] = 점유 유효 시간(접속·입출력 시간 상한의 합보다 길어야 한다 — 설정이 검사).
 */
interface AcquisitionTransportPolicy {
    val transmissionLease: Duration

    /**
     * rename 안전 여유(리뷰 MJ-1): rename 요청을 보내기 직전 남은 lease가 이 값보다 짧으면 rename하지 않는다(= 그 매입사 io-timeout + 시계 차이 허용치).
     * lease 만료 뒤 다른 시도가 같은 임시명을 다시 쓰기 시작할 수 있으므로, rename은 lease 만료보다 이 여유만큼 앞서 끝나야 한다.
     */
    fun renameSafetyMargin(acquirerCode: String): Duration
}
