package com.paycore.payment.payment.domain

import java.math.BigInteger

/**
 * 운영 홈 KPI 한 행(환경 × 통화)의 값(ADMIN-HOME-KPI-001 §3.2·§3.3 — D-ADMIN-HOME-001 ①③). minor unit 정수(BigInteger — float 없음, 통화 간 합산 없음).
 * - [approvedMinor]: 구간에 확정된 AUTHORIZATION 합, [cancelledMinor]: 구간에 확정된 CANCEL 합(둘 다 ≥ 0), [approvedCount]: 구간 승인 중 asOf까지 전액취소되지 않은 수(≥ 0).
 * - [netApprovedMinor] = approved − cancelled(음수 허용 — 이전 날짜 승인의 오늘 취소).
 * - 불변식(§4.3과 같다): 승인 합이 0이면 건수 0(승인 1건 ≥ 1 minor라 건수 ≤ 승인 합), 사실 없는 행 없음(approved > 0 또는 cancelled > 0).
 */
class HomeKpiFigures(val approvedMinor: BigInteger, val cancelledMinor: BigInteger, val approvedCount: Long) {
    init {
        require(approvedMinor.signum() >= 0 && cancelledMinor.signum() >= 0 && approvedCount >= 0) { "home kpi sums and count must not be negative" }
        require(BigInteger.valueOf(approvedCount) <= approvedMinor) { "each counted approval is at least one minor unit" }
        require(approvedMinor.signum() > 0 || cancelledMinor.signum() > 0) { "a home kpi row needs a confirmed fact" }
    }

    val netApprovedMinor: BigInteger get() = approvedMinor.subtract(cancelledMinor)

    override fun equals(other: Any?): Boolean =
        other is HomeKpiFigures && other.approvedMinor == approvedMinor && other.cancelledMinor == cancelledMinor && other.approvedCount == approvedCount

    override fun hashCode(): Int = approvedMinor.hashCode() * 31 + approvedCount.hashCode()

    override fun toString(): String = "HomeKpiFigures(approvedMinor=$approvedMinor, cancelledMinor=$cancelledMinor, approvedCount=$approvedCount)"
}
