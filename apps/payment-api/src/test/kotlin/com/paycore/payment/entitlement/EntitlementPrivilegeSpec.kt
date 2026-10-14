package com.paycore.payment.entitlement

import com.paycore.payment.payment.adapter.persistence.DbAccess
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * V4 runtime 권한의 행위 증거(명세 §12.1 권한): append-only 표(Inbox·revision별 내용·MID 소속)는 runtime 계정으로 UPDATE·DELETE·잠금 읽기가
 * 1142로 거부되고, head는 DELETE만 거부된다(갱신·잠금 읽기는 쓰는 경로). 권한 집합 고정값은 DatabaseAccountSeparationSpec(SHOW GRANTS).
 */
@EntitlementDbTest
class EntitlementPrivilegeSpec(private val db: DbAccess) : FunSpec({

    test("append-only projection tables reject UPDATE, DELETE and FOR UPDATE for the runtime account (1142)") {
        db.withApp { c ->
            with(db) {
                listOf("inbox_event" to "outcome", "entitlement_snapshot" to "content_hash", "entitlement_mid_owner" to "merchant_id").associate { (t, col) ->
                    t to listOf(
                        c.errorCodeOf("UPDATE $t SET $col = $col WHERE 1 = 0"),
                        c.errorCodeOf("DELETE FROM $t WHERE 1 = 0"),
                        runCatching { c.rows("SELECT * FROM $t WHERE 1 = 0 FOR UPDATE") }.exceptionOrNull()?.let { (it as java.sql.SQLException).errorCode },
                    )
                }
            }
        } shouldBe mapOf(
            "inbox_event" to listOf(1142, 1142, 1142),
            "entitlement_snapshot" to listOf(1142, 1142, 1142),
            "entitlement_mid_owner" to listOf(1142, 1142, 1142),
        )
    }

    test("the head cannot be deleted but can be locked for share by the runtime account") {
        db.withApp { c ->
            with(db) {
                c.errorCodeOf("DELETE FROM entitlement_head WHERE 1 = 0") to
                    runCatching { c.rows("SELECT * FROM entitlement_head WHERE 1 = 0 FOR SHARE") }.isSuccess
            }
        } shouldBe (1142 to true)
    }
})
