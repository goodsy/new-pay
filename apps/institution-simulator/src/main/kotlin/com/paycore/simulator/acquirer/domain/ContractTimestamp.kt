package com.paycore.simulator.acquirer.domain

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 파일 계약 v1 시각 표기(UTC `yyyy-MM-ddTHH:mm:ss.SSSZ` — 밀리초 아래가 있으면 거부). */
object ContractTimestamp {
    private val FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    fun format(at: Instant): String {
        require(at.nano % NANOS_PER_MILLI == 0) { "contract timestamps are truncated to milliseconds" }
        return FORMAT.format(at)
    }

    private const val NANOS_PER_MILLI = 1_000_000
}
