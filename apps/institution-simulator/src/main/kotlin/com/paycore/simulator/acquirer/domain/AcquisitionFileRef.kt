package com.paycore.simulator.acquirer.domain

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 매입 파일 ID(파일 계약 v1 — `ACQ.{acquirerCode}.{environment}.{yyyyMMdd}.{vvv}`, ACQUIRING-001 §5)와 파일명 규칙. 가상 매입사가 FINANCIAL 코드와 독립으로 읽는다
 * (계약은 golden 파일로만 공유 — harness test_simulator_isolation).
 * - 업로드 최종명 `{id}.dat`, 업로드 임시명 = 시도별 `.{id}.dat.{n}.part`(Coordinator 결정 (9) Q-2D-1 — n = 시도 번호 1~999999, 10진·앞 0 없음).
 *   옛 공용 임시명 `.{id}.dat.part`는 받지 않는다: 시도마다 따로 쓰게 해야 늦게 깨어난 시도가 다른 시도의 바이트를 rename할 수 없다(I-32, rev 0.7).
 * - 게시: ACK `ACK.{id}.{nn}.dat`, 결과 `RES.{id}.{nn}.dat`(nn = 01..99), 게시 임시명 `.{name}.tmp`(점으로 시작 — 받는 쪽은 무시한다).
 */
data class AcquisitionFileRef(val acquirerCode: String, val environment: String, val businessDate: LocalDate, val version: Int) {
    init {
        require(CODE.matches(acquirerCode)) { "acquirer code must be 1..32 of [A-Z0-9]" }
        require(environment in ENVIRONMENTS) { "environment must be TEST or LIVE" }
        require(version in 1..MAX_VERSION) { "file version must be 001..999" }
    }

    val value: String get() = "ACQ.$acquirerCode.$environment.${businessDate.format(COMPACT)}.${version.toString().padStart(3, '0')}"

    val finalName: String get() = "$value$DAT"

    fun ackName(ackSeq: Int): String = "ACK.$value.${seq(ackSeq)}$DAT"

    fun resultFileId(resultSeq: Int): String = "RES.$value.${seq(resultSeq)}"

    fun resultName(resultSeq: Int): String = resultFileId(resultSeq) + DAT

    override fun toString(): String = value

    companion object {
        const val MAX_SEQ = 99
        private const val MAX_VERSION = 999
        private const val DAT = ".dat"
        private const val PART = ".part"
        private val ATTEMPT = Regex("^[1-9][0-9]{0,5}$")
        private val CODE = Regex("^[A-Z0-9]{1,32}$")
        private val ENVIRONMENTS = setOf("TEST", "LIVE")
        private val ID = Regex("^ACQ\\.([A-Z0-9]{1,32})\\.(TEST|LIVE)\\.([0-9]{8})\\.([0-9]{3})$")
        private val COMPACT: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        fun seq(n: Int): String {
            require(n in 1..MAX_SEQ) { "sequence must be 01..$MAX_SEQ" }
            return n.toString().padStart(2, '0')
        }

        /** 문법 밖이면 null(실제 달력에 없는 날짜·버전 000 포함). */
        fun parseOrNull(id: String): AcquisitionFileRef? {
            val m = ID.matchEntire(id) ?: return null
            val (code, env, ymd, ver) = m.destructured
            val date = try {
                LocalDate.parse(ymd, COMPACT)
            } catch (e: DateTimeParseException) {
                return null
            }
            if (date.format(COMPACT) != ymd) return null
            val version = ver.toInt()
            return if (version in 1..MAX_VERSION) AcquisitionFileRef(code, env, date, version) else null
        }

        /** 업로드 최종명(`{id}.dat`)의 파일 ID. 임시명·다른 이름은 null. */
        fun fromFinalName(name: String): AcquisitionFileRef? = name.takeIf { it.endsWith(DAT) && !it.startsWith(".") }?.removeSuffix(DAT)?.let(::parseOrNull)

        /** 업로드 임시명(`.{id}.dat.{n}.part`)의 파일 ID. 옛 공용 임시명·앞 0·7자리 이상 n은 null. */
        fun fromTempName(name: String): AcquisitionFileRef? {
            if (!name.startsWith(".") || !name.endsWith(PART)) return null
            val stem = name.removePrefix(".").removeSuffix(PART)
            val attempt = stem.substringAfterLast('.', "")
            if (!ATTEMPT.matches(attempt)) return null
            return fromFinalName(stem.removeSuffix(".$attempt"))
        }

        /** 시도별 임시명 → 그 짝 최종명 `{id}.dat`(짝인 쌍만 rename을 허용한다). */
        fun finalNameOfTemp(tempName: String): String? = fromTempName(tempName)?.finalName

        /** 시도 [attempt]의 업로드 임시명. */
        fun tempName(ref: AcquisitionFileRef, attempt: Int): String {
            require(ATTEMPT.matches(attempt.toString())) { "attempt must be 1..999999" }
            return ".${ref.finalName}.$attempt$PART"
        }
    }
}
