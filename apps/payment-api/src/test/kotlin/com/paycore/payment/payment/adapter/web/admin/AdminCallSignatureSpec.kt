package com.paycore.payment.payment.adapter.web.admin

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Duration

/**
 * ADMIN 호출 서명 검증 단위 시험(ADMIN-PAYMENT-OPS-001 §4.2, D-OPS-001). 기대값은 명세 §4.2 표에서 직접 적는다: canonical = tag·METHOD·정규화 경로·t·본문 SHA-256 hex를 LF로,
 * 시각 창 |now − t| ≤ 300초(경계 포함), 판정 순서 문법 → 창 → kid → 서명. 핵심 변이(§7.5 S2 — 경로·본문 hash 제외, 창 `<`↔`≤`)를 잡는다.
 */
class AdminCallSignatureSpec : BehaviorSpec({

    val settings = AdminChannelFixture.settings()
    val now = 1_792_500_300L
    val path = "/internal/v1/admin/operator-cancellations"
    val body = """{"approvalId":"apr_01JC0000000000000000000001"}""".toByteArray()

    fun verify(header: String?, method: String = "POST", p: String = path, b: ByteArray = body, at: Long = now, window: Duration = AdminChannelFixture.WINDOW) =
        AdminCallSignature.verify(header, method, p, b, at, window, settings::key)

    Given("the canonical form (§4.2)") {
        Then("it is tag, METHOD, normalized path, t and the lowercase SHA-256 hex of the body joined by LF with no trailing LF; GET hashes empty bytes") {
            AdminCallSignature.canonical("GET", "/internal/v1/admin/x", "1792500300", ByteArray(0)) shouldBe
                "paycore.admin-call.v1\nGET\n/internal/v1/admin/x\n1792500300\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        }
        Then("a header made by the independent fixture signer is accepted with its key id") {
            verify(AdminChannelFixture.header("POST", path, body, t = now)) shouldBe AdminCallVerification.Accepted(AdminChannelFixture.K1)
            verify(AdminChannelFixture.header("POST", path, body, kid = AdminChannelFixture.K2, t = now)) shouldBe AdminCallVerification.Accepted(AdminChannelFixture.K2)
        }
    }

    Given("each signed element (mutations: path or body hash dropped from the canonical form)") {
        val header = AdminChannelFixture.header("POST", path, body, t = now)
        Then("a different path is SIGNATURE") { verify(header, p = "$path/apr_01JC0000000000000000000001/deadline-confirmation") shouldBe rejected(AdminCallRejection.SIGNATURE) }
        Then("a different body is SIGNATURE") { verify(header, b = body + ' '.code.toByte()) shouldBe rejected(AdminCallRejection.SIGNATURE) }
        Then("a different method is SIGNATURE") { verify(header, method = "PUT") shouldBe rejected(AdminCallRejection.SIGNATURE) }
        Then("a different t inside the window is SIGNATURE") {
            verify(header.replace("t=$now", "t=${now + 1}")) shouldBe rejected(AdminCallRejection.SIGNATURE)
        }
    }

    Given("the clock window of 300 seconds (mutation: < instead of <=)") {
        val header = AdminChannelFixture.header("POST", path, body, t = now)
        Then("exactly 300 seconds either way is accepted") {
            verify(header, at = now + 300).shouldBeInstanceOf<AdminCallVerification.Accepted>()
            verify(header, at = now - 300).shouldBeInstanceOf<AdminCallVerification.Accepted>()
        }
        Then("301 seconds either way is STALE") {
            verify(header, at = now + 301) shouldBe rejected(AdminCallRejection.STALE)
            verify(header, at = now - 301) shouldBe rejected(AdminCallRejection.STALE)
        }
        Then("the window comes from the setting") {
            verify(header, at = now + 11, window = Duration.ofSeconds(10)) shouldBe rejected(AdminCallRejection.STALE)
            verify(header, at = now + 10, window = Duration.ofSeconds(10)).shouldBeInstanceOf<AdminCallVerification.Accepted>()
        }
    }

    Given("the judgement order (grammar -> window -> kid -> signature)") {
        val good = AdminChannelFixture.header("POST", path, body, t = now)
        val v1 = good.substringAfter("v1=")
        Then("a missing or blank header is MALFORMED") {
            verify(null) shouldBe rejected(AdminCallRejection.MALFORMED)
            verify("") shouldBe rejected(AdminCallRejection.MALFORMED)
        }
        Then("a stale header with an unknown kid is STALE (window before kid)") {
            verify("t=${now - 301},kid=adm-unknown,v1=$v1") shouldBe rejected(AdminCallRejection.STALE)
        }
        Then("an unknown kid with a wrong signature is UNKNOWN_KID (kid before signature)") {
            verify("t=$now,kid=adm-unknown,v1=${"0".repeat(64)}") shouldBe rejected(AdminCallRejection.UNKNOWN_KID)
        }
        Then("uppercase hex, spaces, other order, extra fields and an 11-digit t are MALFORMED") {
            listOf(
                good.replace(v1, v1.uppercase()), good.replace(",", ", "), "kid=${AdminChannelFixture.K1},t=$now,v1=$v1", "$good,v2=00",
                "t=0$now,kid=${AdminChannelFixture.K1},v1=$v1", " $good", "$good\n",
            ).forEach { verify(it) shouldBe rejected(AdminCallRejection.MALFORMED) }
        }
    }
})

private fun rejected(reason: AdminCallRejection): AdminCallVerification = AdminCallVerification.Rejected(reason)
