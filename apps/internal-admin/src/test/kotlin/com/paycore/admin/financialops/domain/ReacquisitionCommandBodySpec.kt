package com.paycore.admin.financialops.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.Instant

/**
 * 재매입 실행 명령 본문·내용 지문(ACQ-ADMIN-001 B1-A — FINANCIAL `ReacquisitionCommand`와 코드 공유 없이 같은 계약). 독립 벡터: financial-batch
 * `ReacquisitionExecutionRuleSpec` EX-5(Python hashlib — 길이 접두 canonical, correlationId 제외)와 **같은 입력·같은 값**이어야 한다(D-ADMIN-007: FINANCIAL이 같은 승인 ID 재전송을
 * 내용 hash로 재생·충돌 판정 — ADMIN 실행 행 지문 = 이 값).
 */
class ReacquisitionCommandBodySpec : BehaviorSpec({

    val approvedAt = Instant.parse("2026-11-07T09:00:00.000Z")

    fun body(
        vararg seqs: Int = intArrayOf(1, 3),
        approvalId: String = "apr_01JEXAMPLE",
        requestedBy: String = "adm_req",
        approvedBy: String = "adm_apv",
        status: String = "REJECTED",
        reason: String = "INSTITUTION_REQUESTED",
        evidence: String = "INQ-2026-0001",
        before: Instant = approvedAt.plus(Duration.ofHours(1)),
    ) = ReacquisitionCommandBody(
        approvalId, "ACQ.SIMACQA.TEST.20261105.001", seqs.map { ReacquisitionCommandBody.Item(it, 0, status) }, requestedBy, approvedBy, reason, evidence,
        approvedAt, before,
    )

    Given("RB-1 the FINANCIAL independent vector (EX-5 inputs)") {
        Then("the acd1 content hash equals the FINANCIAL value byte for byte, and any business field changes it") {
            body().contentHash() shouldBe "acd1:8ea4f6b6eafbe81ffe250745743dfee1dff033e6986abc4547239efc95e4cf7f"
            listOf(
                body(1), body(1, 3, requestedBy = "adm_o"), body(1, 3, approvedBy = "adm_o"), body(1, 3, reason = "DATA_CORRECTED_AT_SOURCE"),
                body(1, 3, evidence = "INQ-2026-0002"), body(1, 3, before = approvedAt.plus(Duration.ofHours(2))), body(1, 3, status = "FILE_REJECTED"),
                body(1, 3, approvalId = "apr_01JOTHER"),
            ).forEach { it.contentHash() shouldNotBe body().contentHash() }
        }
    }

    Given("RB-2 the execution POST body (FINANCIAL ReacquisitionCommandParser — every field, no null, millisecond times)") {
        Then("it has exactly the parser's fields; the correlation id is outside the content hash") {
            val tree = JsonMapper.builder().build().readTree(body().json("corr-1"))
            tree.propertyNames().toSet() shouldBe setOf(
                "approvalId", "originalFileId", "items", "requestedBy", "approvedBy", "reasonCode", "evidenceRef", "approvedAt", "executeBefore", "correlationId",
            )
            tree.get("items").toString() shouldBe """[{"seq":1,"expectedGeneration":0,"expectedStatus":"REJECTED"},{"seq":3,"expectedGeneration":0,"expectedStatus":"REJECTED"}]"""
            tree.get("approvedAt").asString() shouldBe "2026-11-07T09:00:00.000Z"
            tree.get("executeBefore").asString() shouldBe "2026-11-07T10:00:00.000Z"
            tree.get("correlationId").asString() shouldBe "corr-1"
            body().json("corr-2").contentEquals(body().json("corr-1")) shouldBe false
        }
    }

    Given("RB-3 malformed commands are refused before anything is stored (same grammar as FINANCIAL — no silent 400 later)") {
        Then("unordered / duplicated / too many items, deadline not after approval, sub-millisecond time, bad formats") {
            shouldThrow<IllegalArgumentException> { body(3, 1) }
            shouldThrow<IllegalArgumentException> { body(1, 1) }
            shouldThrow<IllegalArgumentException> { body(*IntArray(51) { it + 1 }) }
            shouldThrow<IllegalArgumentException> { body(before = approvedAt) }
            shouldThrow<IllegalArgumentException> { body(before = approvedAt.plusNanos(1_500_000)) }
            shouldThrow<IllegalArgumentException> { body(status = "ACCEPTED") }
            shouldThrow<IllegalArgumentException> { body(reason = "lower") }
            shouldThrow<IllegalArgumentException> { body(evidence = "has space") }
            shouldThrow<IllegalArgumentException> { body().json("bad correlation") }
            shouldThrow<IllegalArgumentException> {
                ReacquisitionCommandBody("apr_1", "ACQ.X.TEST.2026.001", listOf(ReacquisitionCommandBody.Item(1, 0, "REJECTED")), "a", "b", "R", "e", approvedAt, approvedAt.plusSeconds(1))
            }
        }
    }
})
