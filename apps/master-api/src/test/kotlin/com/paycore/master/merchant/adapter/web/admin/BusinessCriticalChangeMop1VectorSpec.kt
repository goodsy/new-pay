package com.paycore.master.merchant.adapter.web.admin

import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.ApprovalFingerprint
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

/** BIZ-CRIT-APPROVAL-001 S3: MASTER `mop1` 재계산이 ADMIN `BusinessCriticalChangeCommandBodySpec`과 같은 입력에서 같은 hex(독립 Python 벡터)다 — 코드 공유 없는 두 앱 계약. */
class BusinessCriticalChangeMop1VectorSpec : FunSpec({
    test("ApplyBusinessCriticalChange mop1 vector") {
        ApprovalFingerprint.mop1(
            AdminOperation.APPLY_BUSINESS_CRITICAL_CHANGE, "apr_01JCZ8V3N6Q2R4T7W9XB5D1F3H", "admin:adm_01JCRQ0000000000000000REQ1", "admin:adm_01JCAP0000000000000000APR1",
            Instant.parse("2026-11-10T01:00:00.000Z"), Instant.parse("2026-11-10T02:00:00.000Z"), "BIZ-DOC/2026-0001#p1",
            listOf("biz_01JCBC00000000000000000BC1", "3", "bcp_01JCBC00000000000000000BC1", "h1:" + "ab".repeat(32)),
        ) shouldBe "mop1:20a64696190f7dbc3324820505b0b9420ad972e8b0ff887f8df51abc69607903"
    }
})
