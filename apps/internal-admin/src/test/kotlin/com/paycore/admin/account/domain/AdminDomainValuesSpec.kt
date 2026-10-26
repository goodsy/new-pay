package com.paycore.admin.account.domain

import com.paycore.admin.account.AdminAccountFixture
import com.paycore.admin.account.AdminAccountFixture.NOW
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** account domain 값 객체·권한 표·등록 코드(§4.3.6·§4.5). */
class AdminDomainValuesSpec : FunSpec({

    test("actor IDs are 'adm_' + 26 Crockford base32 characters and are ordered by value") {
        AdminActorId("adm_01JABCDEFGHJKMNPQRSTVWXYZ0").value.length shouldBe 30
        listOf("adm_01jabcdefghjkmnpqrstvwxyz0", "adm_01JABCDEFGHJKMNPQRSTVWXYZ", "usr_01JABCDEFGHJKMNPQRSTVWXYZ0", "adm_01JABCDEFGHIKMNPQRSTVWXYZ0")
            .map { runCatching { AdminActorId(it) }.isFailure } shouldBe List(4) { true }
        (AdminActorId("adm_00000000000000000000000001") < AdminActorId("adm_00000000000000000000000002")) shouldBe true
    }

    test("login names are lower-case ASCII 3-64 characters (upper case is refused, not normalized — D-62)") {
        listOf("ops.kim", "a-b_c", "sec01").map { LoginName(it).value } shouldBe listOf("ops.kim", "a-b_c", "sec01")
        listOf("Admin", "ab", ".lead", "a b", "kim@corp", "x".repeat(65)).map { runCatching { LoginName(it) }.isFailure } shouldBe List(6) { true }
    }

    test("display names are 1-100 characters without surrounding spaces or control characters") {
        DisplayName("홍길동").value shouldBe "홍길동"
        listOf("", " lead", "lead ", "line\nbreak", "x".repeat(101)).map { runCatching { DisplayName(it) }.isFailure } shouldBe List(5) { true }
    }

    test("secrets never appear in toString (password hash, TOTP ciphertext, code hash)") {
        val texts = listOf(AdminAccountFixture.PASSWORD_HASH.toString(), AdminAccountFixture.TOTP.toString(), EnrollmentCodeHash.of("A".repeat(32)).toString())
        texts.forEach { it shouldNotContain "aaaa" }
        texts[2].length shouldBe "EnrollmentCodeHash(".length + 8 + 2
    }

    test("enrollment purposes set exactly their elements (D-ADMIN1-011)") {
        EnrollmentPurpose.entries.associateWith { it.setsPassword to it.setsTotp } shouldBe mapOf(
            EnrollmentPurpose.ENROLL to (true to true), EnrollmentPurpose.PASSWORD_RESET to (true to false),
            EnrollmentPurpose.TOTP_REENROLL to (false to true), EnrollmentPurpose.RECOVERY to (true to true),
        )
    }

    test("enrollment code input is normalized (spaces, hyphens, case) and hashed with SHA-256; other input is not a code") {
        val code = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        EnrollmentCodeHash.normalize("abcd efgh-ijkl mnop qrst uvwx yz23 4567") shouldBe code
        EnrollmentCodeHash.of(code) shouldBe EnrollmentCodeHash.of(EnrollmentCodeHash.normalize(code.lowercase())!!)
        listOf("ABC", code + "A", code.replace('A', '1'), code.replace('B', '0')).map { EnrollmentCodeHash.normalize(it) } shouldBe List(4) { null }
        EnrollmentCodeHash.of(code).bytes.size shouldBe 32
    }

    test("an enrollment code is usable only while unused, unrevoked and before expiry (server clock)") {
        val code = EnrollmentCode.issue(EnrollmentCodeHash.of("A".repeat(32)), AdminAccountFixture.newActorId(), PendingEnrollment(EnrollmentPurpose.ENROLL, "enc_1"), NOW, NOW.plusSeconds(60))
        listOf(code.isUsable(NOW), code.isUsable(NOW.plusSeconds(60)), code.copy(usedAt = NOW).isUsable(NOW), code.copy(revokedAt = NOW).isUsable(NOW)) shouldBe
            listOf(true, false, false, false)
    }

    test("RECOVERY codes need their episode and recorded roles; recorded roles only SECURITY_ADMIN/APPROVER and only on RECOVERY codes") {
        val hash = EnrollmentCodeHash.of("B".repeat(32))
        val target = AdminAccountFixture.newActorId()
        fun code(purpose: EnrollmentPurpose, episode: Long?, roles: Set<AdminRole>?) =
            runCatching { EnrollmentCode(hash, target, purpose, "episode:1", episode, roles, NOW, NOW.plusSeconds(60), null, null) }.isSuccess
        listOf(
            code(EnrollmentPurpose.RECOVERY, 1, setOf(AdminRole.APPROVER)), code(EnrollmentPurpose.RECOVERY, 1, emptySet()), code(EnrollmentPurpose.ENROLL, 1, null),
            code(EnrollmentPurpose.RECOVERY, null, emptySet()), code(EnrollmentPurpose.RECOVERY, 1, null), code(EnrollmentPurpose.ENROLL, 1, emptySet()),
            code(EnrollmentPurpose.RECOVERY, 1, setOf(AdminRole.VIEWER)),
        ) shouldBe listOf(true, true, true, false, false, false, false)
        shouldThrow<IllegalArgumentException> { EnrollmentCode(hash, target, EnrollmentPurpose.ENROLL, "enc_1", null, null, NOW, NOW, null, null) }
    }

    test("permission table (§4.5.1): only ACTIVE accounts, judged by current active roles") {
        val security = AdminAccountFixture.bootstrapped(AdminRole.SECURITY_ADMIN)
        val approver = AdminAccountFixture.bootstrapped(AdminRole.APPROVER)
        val roleless = AdminAccountFixture.enrolledActive()
        fun allowed(account: AdminAccount) = AdminAction.entries.filter { AdminPermission.allows(account, it) }.toSet()
        allowed(security) shouldBe setOf(
            AdminAction.VIEW_MASKED, AdminAction.VIEW_OWN_AUDIT, AdminAction.VIEW_ALL_AUDIT, AdminAction.CREATE_ACCOUNT, AdminAction.REISSUE_ENROLL_CODE,
            AdminAction.SUSPEND_ACCOUNT, AdminAction.REVOKE_ROLE, AdminAction.REQUEST_ADMIN_OWNED_COMMAND,
        )
        allowed(approver) shouldBe setOf(AdminAction.VIEW_MASKED, AdminAction.VIEW_OWN_AUDIT, AdminAction.VIEW_ALL_AUDIT, AdminAction.DECIDE_APPROVAL)
        allowed(roleless) shouldBe emptySet()
        allowed(AdminAccountFixture.applied(security.suspend(AdminAccountFixture.newActorId(), NOW))) shouldBe emptySet()
    }

    test("pending enrollment bases are 1-40 safe characters (approval IDs, issue IDs, episodes)") {
        listOf("enc_01JABCDEFGHJKMNPQRSTVWXYZ0", "apr_01JABCDEFGHJKMNPQRSTVWXYZ0", "episode:12").map { PendingEnrollment(EnrollmentPurpose.ENROLL, it).basis.length } shouldBe
            listOf(30, 30, 10)
        listOf("", "x".repeat(41), "a b", "a'b").map { runCatching { PendingEnrollment(EnrollmentPurpose.ENROLL, it) }.isFailure } shouldBe List(4) { true }
    }
})
