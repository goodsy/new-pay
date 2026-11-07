package com.paycore.admin.financialops.domain

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * 재매입 실행 명령 본문과 내용 지문(ACQ-ADMIN-001 B1-A — FINANCIAL `ReacquisitionCommand`·`ReacquisitionCommandParser`와 **코드 공유 없이** 같은 계약, 독립 벡터
 * `ReacquisitionCommandBodySpec` RB-1 = financial-batch EX-5). 순수 계산(DB·외부 호출 없음 — 승인 TX1 안에서 만든다).
 * - 문법은 FINANCIAL 파서와 같다(여기서 거르지 못하면 FINANCIAL 400 → 승인 소진): 승인·요청자·승인자 ID `[A-Za-z0-9._:-]{1,64}`, 파일 ID 파일 계약 v1, 항목 1..50 순번 오름차순·중복 없음,
 *   기대 상태 REJECTED·FILE_REJECTED·UNKNOWN, 사유 코드 `[A-Z0-9_]{1,32}`, 근거 `[A-Za-z0-9._:#/-]{1,128}`, 시각 ms 단위·기한 > 승인.
 * - [contentHash] = `acd1:` + SHA-256(길이 접두 canonical `<UTF-8 바이트 수>:<값>`) — correlationId 제외. ADMIN 실행 행 지문(admin_owner_execution.fingerprint)이고 FINANCIAL 결정의
 *   contentHash와 대조한다(L-b).
 * 값은 모두 제한된 ASCII라 JSON 이스케이프가 필요 없다. toString에 근거가 없다.
 */
data class ReacquisitionCommandBody(
    val approvalId: String,
    val originalFileId: String,
    val items: List<Item>,
    val requestedBy: String,
    val approvedBy: String,
    val reasonCode: String,
    val evidenceRef: String,
    val approvedAt: Instant,
    val executeBefore: Instant,
) {
    data class Item(val seq: Int, val expectedGeneration: Int, val expectedStatus: String) {
        init {
            require(seq >= 1) { "seq" }
            require(expectedGeneration >= 0) { "generation" }
            require(expectedStatus in STATUSES) { "expected status" }
        }
    }

    init {
        require(ID.matches(approvalId) && ID.matches(requestedBy) && ID.matches(approvedBy)) { "id format" }
        require(FILE_ID.matches(originalFileId)) { "file id format" }
        require(items.size in 1..MAX_ITEMS) { "1..$MAX_ITEMS items" }
        require(items.map { it.seq }.zipWithNext().all { (a, b) -> a < b }) { "ascending seq without duplicates" }
        require(REASON.matches(reasonCode)) { "reason code format" }
        require(EVIDENCE.matches(evidenceRef)) { "evidence format" }
        require(executeBefore.isAfter(approvedAt)) { "deadline after approval" }
        require(approvedAt.nano % NANOS_PER_MILLI == 0 && executeBefore.nano % NANOS_PER_MILLI == 0) { "millisecond precision" }
    }

    fun contentHash(): String {
        val parts = listOf(TAG, VERSION, approvalId, KIND, originalFileId, items.size.toString()) +
            items.flatMap { listOf(it.seq.toString(), it.expectedGeneration.toString(), it.expectedStatus) } +
            listOf(requestedBy, approvedBy, reasonCode, evidenceRef, TIME.format(approvedAt), TIME.format(executeBefore))
        val canonical = parts.joinToString("") { "${it.toByteArray(StandardCharsets.UTF_8).size}:$it" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return PREFIX + digest.joinToString("") { "%02x".format(it) }
    }

    /** 실행·기한 확인 POST 본문(FINANCIAL 파서 필드 전부 — null 없음). */
    fun json(correlationId: String): ByteArray {
        require(CORRELATION.matches(correlationId)) { "correlation id format" }
        val itemsJson = items.joinToString(",", "[", "]") { """{"seq":${it.seq},"expectedGeneration":${it.expectedGeneration},"expectedStatus":"${it.expectedStatus}"}""" }
        return (
            """{"approvalId":"$approvalId","originalFileId":"$originalFileId","items":$itemsJson,"requestedBy":"$requestedBy","approvedBy":"$approvedBy",""" +
                """"reasonCode":"$reasonCode","evidenceRef":"$evidenceRef","approvedAt":"${TIME.format(approvedAt)}","executeBefore":"${TIME.format(executeBefore)}",""" +
                """"correlationId":"$correlationId"}"""
            ).toByteArray(StandardCharsets.UTF_8)
    }

    override fun toString(): String = "ReacquisitionCommandBody(approvalId=$approvalId, originalFileId=$originalFileId, items=${items.size})"

    companion object {
        const val PREFIX = "acd1:"
        const val MAX_ITEMS = 50
        private const val TAG = "paycore.acquisition-ops-decision-content"
        private const val VERSION = "1"
        private const val KIND = "REACQUISITION"
        private const val NANOS_PER_MILLI = 1_000_000
        val STATUSES = setOf("REJECTED", "FILE_REJECTED", "UNKNOWN")
        private val ID = Regex("^[A-Za-z0-9._:-]{1,64}$")
        private val FILE_ID = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")
        private val REASON = Regex("^[A-Z0-9_]{1,32}$")
        private val EVIDENCE = Regex("^[A-Za-z0-9._:#/-]{1,128}$")
        private val CORRELATION = Regex("^[A-Za-z0-9._:-]{1,64}$")
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)
    }
}
