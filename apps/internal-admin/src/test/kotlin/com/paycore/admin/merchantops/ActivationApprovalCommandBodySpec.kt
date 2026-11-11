package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.ActivationApprovalCommandBody
import com.paycore.admin.merchantops.domain.SiteReviewApprovalCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant

/**
 * B3-1 승인 명령 본문·`mop1` 지문(MERCHANT-OPS-002 §9.14·§9.15 — MASTER와 코드 공유 없이 독립 계산). 독립 벡터: Python `hashlib.sha256`으로 따로 계산한 hex
 * (MASTER `ActivationCommandParserSpec`와 같은 입력·같은 값 — Coordinator 계약). 입력: approvalId apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H, requestedBy admin:adm_01JCRQ0000000000000000REQ1,
 * approvedBy admin:adm_01JCAP0000000000000000APR1, approvedAt 2026-11-10T01:00:00.000Z, executeBefore 2026-11-10T02:00:00.000Z.
 * 본문은 근거 공통 9 + **`operation`**(C-39) + 명령 필드(개통 activationId, primarySiteId — C-45 / Site 심사 merchantSiteId, reasonCode).
 */
class ActivationApprovalCommandBodySpec : BehaviorSpec({

    val approvedAt = Instant.parse("2026-11-10T01:00:00.000Z")
    val executeBefore = Instant.parse("2026-11-10T02:00:00.000Z")
    fun activation(evidenceRef: String = "VAN-REPLY/2026-0001", activationId: String = "act_01JCAC00000000000000000AC1", site: String = "site_01JCST00000000000000000ST1") =
        ActivationApprovalCommandBody(
            "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1", approvedAt, executeBefore, evidenceRef, activationId, site,
        )
    fun siteReview(evidenceRef: String = "PG-REVIEW/2026-0001", site: String = "site_01JCST00000000000000000ST1", reason: String = "REVIEW_PASSED") =
        SiteReviewApprovalCommandBody(
            "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1", approvedAt, executeBefore, evidenceRef, site, reason,
        )

    Given("AB-1 independent mop1 vectors (Python hashlib — same values as the MASTER tests)") {
        Then("ApprovePaymentMethodActivation: the vector matches; one evidence character changes it; one command-field character changes it") {
            activation().fingerprint() shouldBe "mop1:1a914b85d99b5430a22bfa3d24522488915633e964b09a79dfd3cfa62d35893f"
            activation("VAN-REPLY/2026-0002").fingerprint() shouldBe "mop1:f01e3cc54b90c5d8a1c76fa95a2c9b09eb79c9857b3d2ec985dc191d845091f5"
            activation(site = "site_01JCST00000000000000000ST2").fingerprint() shouldNotBe activation().fingerprint()
        }
        Then("DecideSiteReview: the vector matches; one evidence character changes it; one site character changes it") {
            siteReview().fingerprint() shouldBe "mop1:3d521c9791ad2a3a71676a65c8e15741dd52c3d400afd88202f1e6db2be12de5"
            siteReview("PG-REVIEW/2026-0002").fingerprint() shouldBe "mop1:8265c84d652a6520dbb68598f1b24571373318e9c887fca95b4e50531e9363de"
            siteReview(site = "site_01JCST00000000000000000ST2").fingerprint() shouldNotBe siteReview().fingerprint()
        }
    }

    Given("AB-2 the stored body (12 string fields, fixed order, operation after the fingerprint, no actor)") {
        Then("activation: exact bytes; malformed ids, a free-text evidence are refused here first; toString has no evidence") {
            String(activation().json()) shouldBe
                """{"idempotencyKey":"mop1.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",""" +
                """"requestedBy":"admin:adm_01JCRQ0000000000000000REQ1","approvedBy":"admin:adm_01JCAP0000000000000000APR1","approvedAt":"2026-11-10T01:00:00.000Z",""" +
                """"executeBefore":"2026-11-10T02:00:00.000Z","evidenceRef":"VAN-REPLY/2026-0001",""" +
                """"approvalFingerprint":"mop1:1a914b85d99b5430a22bfa3d24522488915633e964b09a79dfd3cfa62d35893f","operation":"ApprovePaymentMethodActivation",""" +
                """"activationId":"act_01JCAC00000000000000000AC1","primarySiteId":"site_01JCST00000000000000000ST1"}"""
            shouldThrow<IllegalArgumentException> { activation(activationId = "cra_01JCAC00000000000000000AC1") }
            shouldThrow<IllegalArgumentException> { activation(site = "mer_01JCST00000000000000000ST1") }
            shouldThrow<IllegalArgumentException> { activation("has space") }
            activation().toString().contains("VAN-REPLY") shouldBe false
        }
        Then("site review: exact bytes; only REVIEW_PASSED (C-37 — a reject code cannot travel the 2-person channel, C-40)") {
            String(siteReview().json()) shouldBe
                """{"idempotencyKey":"mop1.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","correlationId":"mop.apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H","approvalId":"apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H",""" +
                """"requestedBy":"admin:adm_01JCRQ0000000000000000REQ1","approvedBy":"admin:adm_01JCAP0000000000000000APR1","approvedAt":"2026-11-10T01:00:00.000Z",""" +
                """"executeBefore":"2026-11-10T02:00:00.000Z","evidenceRef":"PG-REVIEW/2026-0001",""" +
                """"approvalFingerprint":"mop1:3d521c9791ad2a3a71676a65c8e15741dd52c3d400afd88202f1e6db2be12de5","operation":"DecideSiteReview",""" +
                """"merchantSiteId":"site_01JCST00000000000000000ST1","reasonCode":"REVIEW_PASSED"}"""
            shouldThrow<IllegalArgumentException> { siteReview(reason = "DOCUMENT_INSUFFICIENT") }
            shouldThrow<IllegalArgumentException> { siteReview(site = "site_x") }
            siteReview().toString().contains("PG-REVIEW") shouldBe false
        }
    }
})
