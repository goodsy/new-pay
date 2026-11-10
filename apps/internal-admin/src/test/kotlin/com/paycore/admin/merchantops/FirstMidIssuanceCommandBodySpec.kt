package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.FirstMidIssuanceCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/**
 * B2-3 첫 Default MID 발급 명령 본문·`mop1` 지문(§9.3·§9.4 — MASTER와 코드 공유 없이 독립 계산). 독립 벡터: Python `hashlib.sha256`으로 따로 계산한 hex(MASTER
 * `MerchantApprovalCommandParserSpec` B2-3 벡터와 같은 입력·같은 값). 명령 필드 순서 merchantId, contractId, agreementId, revisionNo, primarySiteId.
 */
class FirstMidIssuanceCommandBodySpec : BehaviorSpec({

    fun vector(evidenceRef: String = "MID-2026/0001#p1") = FirstMidIssuanceCommandBody(
        approvalId = "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", requestedBy = "admin:adm_01JCRQ0000000000000000REQ1", approvedBy = "admin:adm_01JCAP0000000000000000APR1",
        approvedAt = Instant.ofEpochMilli(1794272400000), executeBefore = Instant.ofEpochMilli(1794276000000), evidenceRef = evidenceRef,
        merchantId = "mer_01JCME00000000000000000001", contractId = "ctr_01JCCT00000000000000000CT1", agreementId = "csa_01JCCS00000000000000000CS1", revisionNo = 1,
        primarySiteId = "site_01JCST00000000000000000ST1",
    )

    Given("FB-1 the independent vector") {
        Then("IssueFirstDefaultMerchantAccount mop1 matches; one evidence character changes it") {
            vector().fingerprint() shouldBe "mop1:5763462d2ae22f336190ba12a3f873d48651beaf391fd783c020032b96d82a89"
            vector("MID-2026/0001#p2").fingerprint() shouldBe "mop1:1121aa32a7f3aa3e4e50c22b8feba568d3d78d4cd224cf928a443f66027e9b34"
        }
    }

    Given("FB-2 the stored body (14 string fields, fixed order, no actor)") {
        Then("exact bytes; malformed values are refused here first") {
            String(vector().json()) shouldBe
                """{"idempotencyKey":"mop1.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",""" +
                """"requestedBy":"admin:adm_01JCRQ0000000000000000REQ1","approvedBy":"admin:adm_01JCAP0000000000000000APR1","approvedAt":"2026-11-10T01:00:00.000Z",""" +
                """"executeBefore":"2026-11-10T02:00:00.000Z","evidenceRef":"MID-2026/0001#p1",""" +
                """"approvalFingerprint":"mop1:5763462d2ae22f336190ba12a3f873d48651beaf391fd783c020032b96d82a89",""" +
                """"merchantId":"mer_01JCME00000000000000000001","contractId":"ctr_01JCCT00000000000000000CT1","agreementId":"csa_01JCCS00000000000000000CS1",""" +
                """"revisionNo":"1","primarySiteId":"site_01JCST00000000000000000ST1"}"""
            shouldThrow<IllegalArgumentException> { vector().copy(agreementId = "csa_x") }
            shouldThrow<IllegalArgumentException> { vector().copy(primarySiteId = "mer_01JCME00000000000000000001") }
            shouldThrow<IllegalArgumentException> { vector("has space") }
            vector().toString().contains("MID-2026") shouldBe false
        }
    }
})
