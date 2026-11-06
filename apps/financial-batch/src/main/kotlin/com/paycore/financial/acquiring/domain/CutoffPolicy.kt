package com.paycore.financial.acquiring.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 매입 마감 정책(D-ACQ-003 — 사용자 결정: 매입사별 하루 1파일, Asia/Seoul, 마감 시각은 **버전 관리 설정이며 Demo 값**(운영값 아님)).
 * 업무일 = 달력일(Q-CALENDAR 확정 전 Demo 한정 잠정). 업무일 D에 적용하는 버전 = `effectiveFrom ≤ D`인 것 중 가장 늦은 것. 없으면 그 날은 매입 불가(fail-closed).
 * 업무일 D 정기 파일 대상 = `approvedAt ≤ cutoffInstant(D)`(Implementer 해석 I-2).
 */
class CutoffPolicy(versions: List<CutoffPolicyVersion>) {
    val versions: List<CutoffPolicyVersion> = versions.sortedBy { it.effectiveFrom }

    init {
        if (this.versions.isEmpty()) throw AcquisitionContractViolation("at least one cutoff policy version is required")
        if (this.versions.map { it.version }.toSet().size != this.versions.size) throw AcquisitionContractViolation("cutoff policy versions must be unique")
        if (this.versions.map { it.effectiveFrom }.toSet().size != this.versions.size) {
            throw AcquisitionContractViolation("two cutoff policy versions cannot take effect on the same date")
        }
    }

    fun versionFor(businessDate: LocalDate): CutoffPolicyVersion =
        versions.lastOrNull { !it.effectiveFrom.isAfter(businessDate) }
            ?: throw AcquisitionContractViolation("no cutoff policy is in effect for the business date")

    fun cutoffInstant(businessDate: LocalDate): Instant = businessDate.atTime(versionFor(businessDate).cutoffTime).atZone(ZONE).toInstant()

    companion object {
        /** D-ACQ-003: 업무일·마감 시각의 시간대. */
        val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

/** 마감 정책 한 버전. [cutoffTime]은 Asia/Seoul 현지 시각(초 이하 없음). */
data class CutoffPolicyVersion(val version: String, val effectiveFrom: LocalDate, val cutoffTime: LocalTime) {
    init {
        if (!AcquisitionFileHeader.POLICY_VERSION.matches(version)) throw AcquisitionContractViolation("cutoff policy version must be 1..64 characters of [A-Za-z0-9._-]")
        if (cutoffTime.second != 0 || cutoffTime.nano != 0) throw AcquisitionContractViolation("cutoff time is whole minutes")
    }
}
