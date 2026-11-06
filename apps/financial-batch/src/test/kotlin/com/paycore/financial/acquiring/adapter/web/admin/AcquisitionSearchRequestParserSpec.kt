package com.paycore.financial.acquiring.adapter.web.admin

import com.paycore.financial.acquiring.application.provided.AcquisitionFileCursor
import com.paycore.financial.acquiring.application.provided.AcquisitionFileSearchCriteria
import com.paycore.financial.acquiring.application.provided.AcquisitionRecordSearchCriteria
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.LocalDate

/**
 * ACQ-VIEW-001 §3.3·AC 3: 검색 본문 엄격 해석 — 모드(파일 ID 정확 조회 단독 / 그 밖 업무일 기간 필수), 기간(양끝 포함 31일 경계·역전), 형식(날짜·파일 ID·매입사 코드·환경·3축 상태
 * 값 집합·needsAttention = true만·cursor 두 키·limit 1..50), 자료형 강제 변환·`null`·모르는 키·중복 key·뒤따르는 토큰·잘못된 UTF-8 거부. 레코드 쪽 본문도 같은 규칙.
 */
class AcquisitionSearchRequestParserSpec : BehaviorSpec({

    fun files(json: String) = AcquisitionSearchRequestParser.files(json.toByteArray(Charsets.UTF_8))
    fun records(json: String) = AcquisitionSearchRequestParser.records(json.toByteArray(Charsets.UTF_8))
    val day = LocalDate.parse("2026-11-05")
    val fileId = "ACQ.SIMACQA.TEST.20261105.001"

    Given("well-formed file search bodies") {
        Then("a period search with every filter and a cursor") {
            files(
                """{"businessDateFrom":"2026-10-06","businessDateTo":"2026-11-05","acquirerCode":"SIMACQA","environment":"TEST","transmissionState":"TRANSMITTED",""" +
                    """"receiptState":"RECEIVED","resultState":"COMPLETE","needsAttention":true,"after":{"businessDate":"2026-11-05","fileId":"$fileId"},"limit":50}""",
            ) shouldBe AcquisitionFileSearchCriteria(
                businessDateFrom = LocalDate.parse("2026-10-06"), businessDateTo = day, acquirerCode = "SIMACQA", environment = "TEST", transmissionState = "TRANSMITTED",
                receiptState = "RECEIVED", resultState = "COMPLETE", needsAttention = true, after = AcquisitionFileCursor(day, fileId), limit = 50,
            )
        }
        Then("one day (from = to), the default limit 20, and the exact file lookup without a period") {
            files("""{"businessDateFrom":"2026-11-05","businessDateTo":"2026-11-05"}""") shouldBe AcquisitionFileSearchCriteria(businessDateFrom = day, businessDateTo = day)
            files("""{"fileId":"$fileId"}""") shouldBe AcquisitionFileSearchCriteria(fileId = fileId)
        }
    }

    Given("malformed file search bodies") {
        val period = """"businessDateFrom":"2026-11-01","businessDateTo":"2026-11-05""""
        val bad = mapOf(
            "not json" to "x",
            "array" to "[]",
            "empty object (no period)" to "{}",
            "only one end" to """{"businessDateFrom":"2026-11-01"}""",
            "32 days" to """{"businessDateFrom":"2026-10-05","businessDateTo":"2026-11-05"}""",
            "reversed" to """{"businessDateFrom":"2026-11-06","businessDateTo":"2026-11-05"}""",
            "impossible date" to """{"businessDateFrom":"2026-02-30","businessDateTo":"2026-03-01"}""",
            "date with time" to """{"businessDateFrom":"2026-11-01T00:00:00Z","businessDateTo":"2026-11-05"}""",
            "file id with a period" to """{"fileId":"$fileId",$period}""",
            "file id with a filter" to """{"fileId":"$fileId","environment":"TEST"}""",
            "file id grammar" to """{"fileId":"ACQ.simacqa.TEST.20261105.001"}""",
            "unknown key" to """{$period,"merchantRef":"m"}""",
            "duplicate key" to """{$period,"environment":"TEST","environment":"LIVE"}""",
            "null" to """{$period,"environment":null}""",
            "trailing token" to """{$period} {}""",
            "environment value" to """{$period,"environment":"test"}""",
            "acquirer code grammar" to """{$period,"acquirerCode":"sim acq"}""",
            "transmission state value" to """{$period,"transmissionState":"SENT"}""",
            "receipt state value" to """{$period,"receiptState":"TRANSMITTED"}""",
            "result state value" to """{$period,"resultState":"UNKNOWN"}""",
            "needsAttention false" to """{$period,"needsAttention":false}""",
            "needsAttention string" to """{$period,"needsAttention":"true"}""",
            "limit 0" to """{$period,"limit":0}""",
            "limit 51" to """{$period,"limit":51}""",
            "limit string" to """{$period,"limit":"5"}""",
            "limit decimal" to """{$period,"limit":5.0}""",
            "cursor extra key" to """{$period,"after":{"businessDate":"2026-11-05","fileId":"$fileId","x":1}}""",
            "cursor missing key" to """{$period,"after":{"businessDate":"2026-11-05"}}""",
            "cursor file id grammar" to """{$period,"after":{"businessDate":"2026-11-05","fileId":"x"}}""",
        )
        Then("each is refused (null — 400 REQUEST_MALFORMED, no field named)") {
            bad.forEach { (name, json) -> withClue(name) { files(json).shouldBeNull() } }
            AcquisitionSearchRequestParser.files(byteArrayOf(0x7b, 0xff.toByte(), 0xfe.toByte(), 0x7d)).shouldBeNull()
        }
    }

    Given("record search bodies") {
        Then("an empty object is the first page (default limit 20); status, afterSeq and limit are read exactly") {
            records("{}") shouldBe AcquisitionRecordSearchCriteria()
            records("""{"status":"UNKNOWN","afterSeq":40,"limit":50}""") shouldBe AcquisitionRecordSearchCriteria("UNKNOWN", 40, 50)
            AcquisitionRecordSearchCriteria.RECORD_STATUSES.forEach { records("""{"status":"$it"}""")!!.status shouldBe it }
        }
        Then("malformed ones are refused") {
            listOf(
                "", "[]", """{"status":"PENDING"}""", """{"afterSeq":-1}""", """{"afterSeq":"1"}""", """{"limit":51}""", """{"limit":0}""", """{"status":null}""",
                """{"x":1}""", """{"limit":1,"limit":2}""",
            ).forEach { json -> withClue(json) { records(json).shouldBeNull() } }
        }
        Then("file ids in paths follow the file contract v1 grammar") {
            AcquisitionSearchRequestParser.FILE_ID.matches(fileId) shouldBe true
            listOf("ACQ.SIMACQA.TEST.20261105.01", "ACQ.SIMACQA.PROD.20261105.001", "../x", "ACQ.SIMACQA.TEST.20261105.001.dat").forEach {
                AcquisitionSearchRequestParser.FILE_ID.matches(it) shouldBe false
            }
        }
    }
})
