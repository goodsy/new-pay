package com.paycore.admin.financialops.adapter.integration

import tools.jackson.databind.JsonNode

/**
 * FINANCIAL 매입 현황 응답 허용 필드 모양(ACQ-VIEW-001 §4.1–§4.3·§4.6 — fail-closed, merchantops `MasterResponseShape`와 같은 방식). 객체는 **정해진 키 집합과 정확히 같아야** 하고
 * (알 수 없는 필드 = 거부: FINANCIAL이 실수로 새 값을 내보내도 화면으로 흘리지 않는다), 값은 형식 정규식·값 집합·정수 범위·불린·목록 길이 상한으로 검사한다. 통과하면 Kotlin 기본
 * 값(String·Long·Boolean·null·List·LinkedHashMap)으로 다시 만든다. **D-ACQV-002 값**(승인번호·기관 거래번호·원 기관 거래번호·발급사·카드 종류·할부)과 송신자 ID·파일 바이트·응답
 * 파일명은 어느 모양에도 없다. 결과 행의 응답 참조는 `responseRef {kind, sequenceNo} | null`(리뷰 MINOR-2 계약).
 *
 * **문법 = FINANCIAL 저장 문법과 정확히 같다**(B2 리뷰 MAJOR-1 — 더 좁으면 FINANCIAL이 정상 저장한 값이 화면에서 영구 503이 되고, 더 넓으면 FINANCIAL이 저장할 수 없는 값을 받아 준다):
 * 식별자 = Domain `AcquisitionText.ID`(`#` 포함 64자), 기관 코드 = `CODE`, 사유 코드(결과 행·ACK) = `TOKEN`(숫자 시작 허용 — 예 `05`·`1001`), 열거 값 = 각 열의 CHECK 값 집합(V5·V7·V9·V10·V14),
 * 정수 = CHECK 범위(fileVersion 1..999, 응답 sequenceNo 1..99). 대조표는 명세 §4.7. FINANCIAL이 값 집합을 늘리면 이 표도 함께 바꾼다(그 전까지는 fail-closed 503).
 */
internal sealed interface FinancialResponseShape {
    /** 검사·변환. 위반이면 [IllegalArgumentException]. */
    fun read(node: JsonNode?): Any?

    class Obj(private val fields: Map<String, FinancialResponseShape>) : FinancialResponseShape {
        override fun read(node: JsonNode?): Map<String, Any?> {
            require(node != null && node.isObject) { "object" }
            require(node.propertyNames().toSet() == fields.keys) { "fields" }
            return fields.entries.associateTo(LinkedHashMap()) { (name, shape) -> name to shape.read(node.get(name)) }
        }
    }

    class Arr(private val item: FinancialResponseShape, private val max: Int) : FinancialResponseShape {
        override fun read(node: JsonNode?): List<Any?> {
            require(node != null && node.isArray && node.size() <= max) { "array" }
            return node.iterator().asSequence().map { item.read(it) }.toList()
        }
    }

    class Text(private val format: Regex) : FinancialResponseShape {
        override fun read(node: JsonNode?): String {
            require(node != null && node.isString) { "string" }
            return node.asString().also { require(format.matches(it)) { "format" } }
        }
    }

    class Whole(private val min: Long, private val max: Long = Long.MAX_VALUE) : FinancialResponseShape {
        override fun read(node: JsonNode?): Long {
            require(node != null && node.isIntegralNumber && node.canConvertToLong()) { "integer" }
            return node.asLong().also { require(it in min..max) { "range" } }
        }
    }

    data object Flag : FinancialResponseShape {
        override fun read(node: JsonNode?): Boolean {
            require(node != null && node.isBoolean) { "boolean" }
            return node.asBoolean()
        }
    }

    class Nullable(private val inner: FinancialResponseShape) : FinancialResponseShape {
        override fun read(node: JsonNode?): Any? {
            require(node != null) { "missing" }
            return if (node.isNull) null else inner.read(node)
        }
    }

    companion object {
        /** 파일 계약 v1 파일 ID 문법(FINANCIAL `AcquisitionSearchRequestParser.FILE_ID`와 같다). */
        val FILE_ID_FORMAT = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")
        private val FILE_ID = Text(FILE_ID_FORMAT)
        private val DATE = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))
        private val TIME = Text(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$"))
        /** Domain `AcquisitionText.TOKEN` = V10 `ck_acquisition_record_result_reason`(ACK 사유는 같은 Domain 검사·VARCHAR(32)). */
        private val REASON_CODE = Text(Regex("^[A-Z0-9_]{1,32}$"))

        /** Domain `AcquisitionText.CODE` = V5·V10 acquirer_code CHECK. */
        private val INSTITUTION_CODE = Text(Regex("^[A-Z0-9]{1,32}$"))

        /** Domain `AcquisitionText.ID`(거래 ID·원거래 ID·가맹점 참조·재매입 근거 `fileId#seq`) — 열은 모두 VARCHAR(64). */
        private val REF = Text(Regex("^[A-Za-z0-9._:#-]{1,64}$"))

        /** Domain `AcquisitionFileHeader.POLICY_VERSION`(cutoff_policy_version VARCHAR(64)). */
        private val VERSION_NAME = Text(Regex("^[A-Za-z0-9._-]{1,64}$"))
        private val SHA256 = Text(Regex("^[0-9a-f]{64}$"))
        private val COUNT = Whole(0)
        private val POSITIVE = Whole(1)
        private val FILE_VERSION = Whole(1, 999)
        private val RESPONSE_SEQUENCE = Whole(1, 99)
        private val SIGNED = Whole(Long.MIN_VALUE)

        private fun oneOf(vararg values: String) = Text(Regex("^(" + values.joinToString("|") + ")$"))
        private fun obj(vararg fields: Pair<String, FinancialResponseShape>) = Obj(linkedMapOf(*fields))
        private fun opt(inner: FinancialResponseShape) = Nullable(inner)

        private val ENVIRONMENT = oneOf("TEST", "LIVE")
        private val TRANSMISSION = oneOf("SEALED", "TRANSMITTING", "TRANSMITTED", "TRANSMISSION_UNKNOWN", "TRANSMISSION_QUARANTINED")
        private val RECEIPT = oneOf("NONE", "RECEIVED", "FILE_REJECTED", "RECEIPT_CONFLICT")
        private val RESULT_AXIS = oneOf("AWAITING", "COMPLETE", "DEADLINE_PASSED")
        private val RECORD_STATUS = oneOf("AWAITING", "ACCEPTED", "REJECTED", "FILE_REJECTED", "UNKNOWN", "CONFLICT")
        private val ATTENTION = oneOf(
            "TRANSMISSION_UNKNOWN", "TRANSMISSION_QUARANTINED", "RECEIPT_CONFLICT", "RESPONSE_QUARANTINED", "RECORD_UNKNOWN", "RECORD_CONFLICT",
            "RESULT_DEADLINE_PASSED", "RESULT_OVERDUE",
        )
        private val RESPONSE_KIND = oneOf("ACK", "RESULT")

        // 전송 시도·결과(V7 + V9 CHECK).
        private val RESUME_STATE = oneOf("SEALED", "TRANSMISSION_UNKNOWN")
        private val ATTEMPT_OUTCOME = oneOf("DELIVERED", "NOT_DELIVERED", "UNKNOWN", "CONTENT_MISMATCH", "SUPERSEDED")
        private val ATTEMPT_OBSERVED = oneOf("DELIVERED", "NOT_DELIVERED", "UNKNOWN", "CONTENT_MISMATCH")
        private val ATTEMPT_EVIDENCE = oneOf("RENAMED_AND_VERIFIED", "VERIFIED_FINAL_PRESENT", "RENAMED")
        private val ATTEMPT_REASON = oneOf(
            "CONNECT_FAILED", "HOST_KEY_REJECTED", "AUTHENTICATION_FAILED", "UPLOAD_FAILED", "RENAME_REFUSED", "RENAME_OUTCOME_UNKNOWN", "VERIFY_FAILED",
            "RENAMED_BUT_ABSENT", "LEASE_EXHAUSTED", "INTERNAL_ERROR",
        )
        private val RESULTING_STATE = oneOf("SEALED", "TRANSMITTING", "TRANSMITTED", "TRANSMISSION_UNKNOWN", "TRANSMISSION_QUARANTINED")

        // 기관 응답·결과 행(V10 + V14 CHECK).
        private val DISPOSITION = oneOf("APPLIED", "QUARANTINED")
        private val QUARANTINE_REASON = oneOf(
            "FORMAT_INVALID", "NAME_MISMATCH", "UNKNOWN_FILE", "NAME_REUSED", "HASH_MISMATCH", "COUNT_MISMATCH", "RECORD_MISMATCH", "RECEIPT_CONFLICT",
            "ACK_CONFLICT", "AFTER_CONFLICT", "TRANSMISSION_QUARANTINED",
        )
        private val ACK_OUTCOME = oneOf("RECEIVED", "FILE_REJECTED", "RECEIPT_CONFLICT")
        private val RESPONSE_EVIDENCE = oneOf("ACK_HASH_MATCH", "RESULT_HASH_MATCH")
        private val RESULT_SOURCE = oneOf("RESULT_FILE", "FILE_REJECTED", "DEADLINE")

        /** 목록 항목 필드(§4.1) — 상세의 `file`은 이 필드 뒤에 봉인 근거 6칸이 더 붙는다. */
        private val ITEM_FIELDS: List<Pair<String, FinancialResponseShape>> = listOf(
            "fileId" to FILE_ID, "acquirerCode" to INSTITUTION_CODE, "environment" to ENVIRONMENT, "businessDate" to DATE, "fileVersion" to FILE_VERSION,
            "originalFileId" to opt(FILE_ID), "recordCount" to COUNT, "saleCount" to COUNT, "saleAmountMinor" to COUNT, "cancelCount" to COUNT,
            "cancelAmountMinor" to COUNT, "netAmountMinor" to SIGNED, "currency" to oneOf("KRW"), "transmissionState" to TRANSMISSION, "receiptState" to RECEIPT,
            "resultState" to RESULT_AXIS, "sealedAt" to TIME, "transmittedAt" to opt(TIME),
            "recordStatusCounts" to obj(
                "awaiting" to COUNT, "accepted" to COUNT, "rejected" to COUNT, "fileRejected" to COUNT, "unknown" to COUNT, "conflict" to COUNT,
            ),
            "attention" to Arr(ATTENTION, max = 8),
            "reacquisitionCandidate" to Flag,
        )

        private val RECORD: FinancialResponseShape = obj(
            "seq" to POSITIVE, "recordType" to oneOf("SALE", "CANCEL"), "transactionId" to REF, "originalTransactionId" to opt(REF), "merchantAccountId" to REF,
            "institutionTransactionDate" to DATE, "eventAt" to TIME, "currency" to oneOf("KRW"), "approvedAmountMinor" to COUNT,
            "nettedCancelAmountMinor" to COUNT, "recordAmountMinor" to COUNT, "generation" to COUNT, "reacquisitionOf" to opt(REF),
            "status" to RECORD_STATUS,
            "results" to Arr(
                obj(
                    "entryNo" to POSITIVE, "result" to oneOf("ACCEPTED", "REJECTED", "UNKNOWN", "CONFLICT"), "observedResult" to opt(oneOf("ACCEPTED", "REJECTED")),
                    "reasonCode" to opt(REASON_CODE), "source" to RESULT_SOURCE, "responseRef" to opt(obj("kind" to RESPONSE_KIND, "sequenceNo" to RESPONSE_SEQUENCE)),
                    "recognizedAt" to TIME,
                ),
                max = 100,
            ),
        )

        val PAGE: FinancialResponseShape = obj(
            "items" to Arr(Obj(linkedMapOf(*ITEM_FIELDS.toTypedArray())), max = 50),
            "nextCursor" to opt(obj("businessDate" to DATE, "fileId" to FILE_ID)),
        )

        val RECORDS: FinancialResponseShape = obj("items" to Arr(RECORD, max = 50), "nextCursor" to opt(POSITIVE))

        val DETAIL: FinancialResponseShape = obj(
            "file" to Obj(
                linkedMapOf(
                    *ITEM_FIELDS.toTypedArray(),
                    "cutoffPolicyVersion" to VERSION_NAME, "cutoffAt" to TIME, "contentSha256" to SHA256, "contentBytes" to COUNT,
                    "transmissionLeaseUntil" to opt(TIME), "updatedAt" to TIME,
                ),
            ),
            "attempts" to obj(
                "total" to COUNT,
                "latest" to Arr(
                    obj(
                        "attemptNo" to POSITIVE, "resumeState" to RESUME_STATE, "claimedAt" to TIME, "leaseUntil" to TIME, "outcome" to opt(ATTEMPT_OUTCOME),
                        "observed" to opt(ATTEMPT_OBSERVED), "evidence" to opt(ATTEMPT_EVIDENCE), "reason" to opt(ATTEMPT_REASON), "resultingState" to opt(RESULTING_STATE),
                        "decidedAt" to opt(TIME),
                    ),
                    max = 20,
                ),
            ),
            "responses" to obj(
                "total" to COUNT, "quarantined" to COUNT,
                "latest" to Arr(
                    obj(
                        "kind" to RESPONSE_KIND, "sequenceNo" to RESPONSE_SEQUENCE, "disposition" to DISPOSITION, "quarantineReason" to opt(QUARANTINE_REASON),
                        "ackOutcome" to opt(ACK_OUTCOME), "ackReasonCode" to opt(REASON_CODE), "evidence" to opt(RESPONSE_EVIDENCE), "institutionAt" to opt(TIME),
                        "observedAt" to TIME,
                    ),
                    max = 20,
                ),
            ),
            "records" to RECORDS,
            "readAt" to TIME,
        )
    }
}
