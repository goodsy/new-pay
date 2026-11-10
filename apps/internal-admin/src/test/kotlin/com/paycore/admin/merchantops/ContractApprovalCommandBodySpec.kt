package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.ContractApprovalCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

/**
 * MERCHANT-OPS-002 B2-2 계약 승인 명령 본문·`mop1` 지문(§9.3 C-23 — ADMIN이 MASTER와 **코드 공유 없이** 독립 계산). 독립 벡터는 MASTER `MerchantApprovalCommandParserSpec`과 같은
 * 입력·같은 hex(Coordinator가 전달한 시험 벡터 — 명세 확정 정규 문자열). 명시 Fixture: 승인 `apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H`, 요청자 `…REQ1`, 승인자 `…APR1`,
 * 승인 1794272400000 ms, 기한 +1시간, 근거 `CONTRACT-2026/0001#p1`, 계약 `ctr_01JCCT00000000000000000CT1` Revision 2.
 */
class ContractApprovalCommandBodySpec : BehaviorSpec({

    fun vector(evidenceRef: String = "CONTRACT-2026/0001#p1", approvedAt: Instant = Instant.ofEpochMilli(1794272400000), executeBefore: Instant = Instant.ofEpochMilli(1794276000000)) =
        ContractApprovalCommandBody(
            approvalId = "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", requestedBy = "admin:adm_01JCRQ0000000000000000REQ1", approvedBy = "admin:adm_01JCAP0000000000000000APR1",
            approvedAt = approvedAt, executeBefore = executeBefore, evidenceRef = evidenceRef, contractId = "ctr_01JCCT00000000000000000CT1", revisionNo = 2,
        )

    Given("MB-1 the independent mop1 vector (C-23 — same input and hex as the MASTER parser spec)") {
        Then("the fingerprint is the agreed value; one evidence character changes it") {
            vector().fingerprint() shouldBe "mop1:f1e2434f5df65194d29af33187e5fb1d947d48d11a7cc444c5764a639c106695"
            vector(evidenceRef = "CONTRACT-2026/0001#p2").fingerprint() shouldBe "mop1:4ffc17ff7a0f5e2f1c1ddecc6ba4057683e3f57c6c89bf40d85723b295b4123d"
        }
    }

    Given("MB-2 the stored request body (exactly 11 string fields in a fixed order, no actor field — C-17)") {
        Then("key = mop1.<approvalId>, correlation = mop.<approvalId>, times with milliseconds even when they are .000, revisionNo as a decimal string") {
            String(vector().json()) shouldBe
                """{"idempotencyKey":"mop1.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",""" +
                """"requestedBy":"admin:adm_01JCRQ0000000000000000REQ1","approvedBy":"admin:adm_01JCAP0000000000000000APR1","approvedAt":"2026-11-10T01:00:00.000Z",""" +
                """"executeBefore":"2026-11-10T02:00:00.000Z","evidenceRef":"CONTRACT-2026/0001#p1",""" +
                """"approvalFingerprint":"mop1:f1e2434f5df65194d29af33187e5fb1d947d48d11a7cc444c5764a639c106695",""" +
                """"contractId":"ctr_01JCCT00000000000000000CT1","revisionNo":"2"}"""
            (String(vector().json()).contains("\"actor\"")) shouldBe false
            vector().json().contentEquals(vector().json()) shouldBe true
        }
        Then("formats the MASTER parser would refuse are refused here first (no approval is spent on a 400)") {
            shouldThrow<IllegalArgumentException> { vector(evidenceRef = "has space") }
            shouldThrow<IllegalArgumentException> { vector(evidenceRef = "x".repeat(129)) }
            shouldThrow<IllegalArgumentException> { vector(executeBefore = Instant.ofEpochMilli(1794272400000)) }
            shouldThrow<IllegalArgumentException> { vector(approvedAt = Instant.ofEpochMilli(1794272400000).plusNanos(1)) }
            shouldThrow<IllegalArgumentException> { vector().copy(requestedBy = "admin:adm_01jcrq0000000000000000req1") }
            shouldThrow<IllegalArgumentException> { vector().copy(revisionNo = 0) }
            vector().copy(revisionNo = Int.MAX_VALUE).fingerprint() shouldNotBe vector().fingerprint()
        }
        Then("toString carries no evidence reference") {
            vector().toString().contains("CONTRACT-2026") shouldBe false
        }
    }
})
