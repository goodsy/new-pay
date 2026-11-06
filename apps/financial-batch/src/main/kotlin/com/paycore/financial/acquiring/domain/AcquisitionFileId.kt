package com.paycore.financial.acquiring.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 매입 파일 ID(파일 계약 v1 §5.1 — Implementer 해석, 가역). (매입사, 환경, 업무일)당 정기 파일은 버전 1 하나(D-ACQ-003), 재매입은 같은 업무일의 다음 버전(D-ACQ-004·
 * D-ACQ-011 — 배치2). [value] = `ACQ.{acquirerCode}.{environment}.{yyyyMMdd}.{vvv}`, 업로드 최종명 [fileName] = `{value}.dat`.
 */
data class AcquisitionFileId(
    val acquirerCode: String,
    val environment: AcquisitionEnvironment,
    val businessDate: LocalDate,
    val version: Int,
) {
    init {
        AcquisitionText.code("acquirerCode", acquirerCode)
        if (version !in 1..MAX_VERSION) throw AcquisitionContractViolation("file version must be within 1..$MAX_VERSION")
    }

    val value: String get() = "ACQ.$acquirerCode.${environment.name}.${BASIC_DATE.format(businessDate)}.${versionText}"

    val versionText: String get() = version.toString().padStart(3, '0')

    val fileName: String get() = "$value.dat"

    override fun toString(): String = value

    companion object {
        const val MAX_VERSION = 999
        const val REGULAR_VERSION = 1
        private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE
        private val PATTERN = Regex("^ACQ\\.([A-Z0-9]{1,32})\\.(TEST|LIVE)\\.([0-9]{8})\\.([0-9]{3})$")

        fun parse(value: String): AcquisitionFileId {
            val m = PATTERN.matchEntire(value) ?: throw AcquisitionContractViolation("file id does not match the acquisition file v1 grammar")
            val (acquirer, environment, date, version) = m.destructured
            val parsedDate = runCatching { LocalDate.parse(date, BASIC_DATE) }.getOrNull()
                ?: throw AcquisitionContractViolation("file id business date is not a calendar date")
            return AcquisitionFileId(acquirer, AcquisitionEnvironment.valueOf(environment), parsedDate, version.toInt())
        }
    }
}

/** 가맹점 환경 축(D-CPD-001 — TEST/LIVE). LIVE 매입은 기관 계약번호 전달 전까지 차단한다(D-ACQ-008). */
enum class AcquisitionEnvironment { TEST, LIVE }
