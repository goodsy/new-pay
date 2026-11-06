package com.paycore.financial.acquiring.domain

import java.time.Instant

/**
 * 매입 파일 헤더(파일 계약 v1 §5.2 `H|…`). [senderId] = PG 송신자 ID(설정 — Demo 값, 실제 등록 ID는 LIVE Gate), [sealedAt] = 봉인 시각(UTC ms),
 * [cutoffPolicyVersion] = 이 파일 대상을 정한 마감 정책 버전(D-ACQ-003 — 버전 관리 설정), [originalFileId] = 재매입 파일만(원 파일 참조).
 */
data class AcquisitionFileHeader(
    val fileId: AcquisitionFileId,
    val senderId: String,
    val sealedAt: Instant,
    val cutoffPolicyVersion: String,
    val originalFileId: AcquisitionFileId?,
) {
    init {
        if (!SENDER.matches(senderId)) throw AcquisitionContractViolation("senderId must be 1..16 characters of [A-Z0-9]")
        if (!POLICY_VERSION.matches(cutoffPolicyVersion)) throw AcquisitionContractViolation("cutoffPolicyVersion must be 1..64 characters of [A-Za-z0-9._-]")
        if (sealedAt.nano % NANOS_PER_MILLI != 0) throw AcquisitionContractViolation("sealedAt must be truncated to milliseconds")
        if ((fileId.version == AcquisitionFileId.REGULAR_VERSION) != (originalFileId == null)) {
            throw AcquisitionContractViolation("only a re-acquisition version refers to an original file")
        }
        originalFileId?.let {
            if (it.acquirerCode != fileId.acquirerCode || it.environment != fileId.environment || it.businessDate != fileId.businessDate || it.version >= fileId.version) {
                throw AcquisitionContractViolation("a re-acquisition version refers to an earlier version of the same lane and business date")
            }
        }
    }

    companion object {
        val SENDER = Regex("^[A-Z0-9]{1,16}$")
        val POLICY_VERSION = Regex("^[A-Za-z0-9._-]{1,64}$")
        private const val NANOS_PER_MILLI = 1_000_000
    }
}
