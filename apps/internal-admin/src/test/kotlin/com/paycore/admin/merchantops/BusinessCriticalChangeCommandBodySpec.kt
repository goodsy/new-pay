package com.paycore.admin.merchantops

import com.paycore.admin.merchantops.domain.BusinessCriticalChangeCommandBody
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

/**
 * BIZ-CRIT-APPROVAL-001 S3 실행 본문·`mop1` 지문(§3.2). 아래 hex는 Python `hashlib.sha256`으로 따로 계산한 독립 벡터이고 MASTER `BusinessCriticalChangeMop1VectorSpec`과 같은 입력·같은 hex다
 * (코드 공유 없는 두 앱 계약). 본문 필드 순서·번호·내용 필드 부재도 고정한다.
 */
class BusinessCriticalChangeCommandBodySpec : BehaviorSpec({

    fun body(baseVersion: Long = 3, businessId: String = "biz_01JCBC00000000000000000BC1", digest: String = "h1:" + "ab".repeat(32)) = BusinessCriticalChangeCommandBody(
        "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
        Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), "BIZ-DOC/2026-0001#p1", businessId, baseVersion, "bcp_01JCBC00000000000000000BC1", digest,
    )

    Given("the independent mop1 vector shared with MASTER") {
        Then("hashes to the fixed hex; a one-field change gives another fingerprint") {
            body().fingerprint() shouldBe "mop1:20a64696190f7dbc3324820505b0b9420ad972e8b0ff887f8df51abc69607903"
            (body(baseVersion = 4).fingerprint() == body().fingerprint()) shouldBe false
        }
    }

    Given("the JSON body") {
        Then("field order = evidence 9 + operation + businessId, baseVersion(decimal string), proposalId, contentDigest; no number or content field; toString hides evidence and digest") {
            val node = JsonMapper.builder().build().readTree(body().json())
            node.propertyNames().toList() shouldBe listOf(
                "idempotencyKey", "correlationId", "approvalId", "requestedBy", "approvedBy", "approvedAt", "executeBefore", "evidenceRef", "approvalFingerprint", "operation",
                "businessId", "baseVersion", "proposalId", "contentDigest",
            )
            listOf("operation", "baseVersion", "approvalFingerprint").map { node.get(it).asString() } shouldBe listOf("ApplyBusinessCriticalChange", "3", body().fingerprint())
            body().toString().let { listOf("BIZ-DOC", "abab").filter(it::contains) } shouldBe emptyList()
        }
        Then("malformed values are refused before MASTER (mer_ id, negative version, m1 digest)") {
            listOf({ body(businessId = "mer_01JCBC00000000000000000BC1") }, { body(baseVersion = -1) }, { body(digest = "m1:" + "ab".repeat(32)) })
                .forEach { shouldThrow<IllegalArgumentException> { it() } }
        }
    }
})
