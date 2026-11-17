package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.persistence.DbAccess
import com.paycore.payment.payment.application.required.MerchantCodePort
import java.time.LocalDateTime
import java.util.UUID

/**
 * MERCHANT-CODE-001 MC-4 시험 대역(src/test 전용). [NO_MERCHANT_CODE] = 가맹점 코드가 전파되지 않은 MID(v1~v3 head·head 없음 — 외부 필드 생략 경로).
 * 웹훅·응답 코드가 시험 주제가 아닌 기존 시험이 쓴다(그 시험의 기대값은 코드 없는 v1 모양 그대로).
 */
val NO_MERCHANT_CODE: MerchantCodePort = MerchantCodePort { null }

/**
 * MERCHANT-CODE-001 MC-4 DB 시험 준비: MID의 권리 투영 head를 root 연결로 직접 넣고 바꾼다(시험 fixture — 운영 경로는 서명 Inbox, `SchemaRolloutOrderV4Spec`가 그 경로를 본다).
 * 이 fixture를 쓰는 컨텍스트의 eligibility·승인 판정은 시험 대역이라 head 내용(hash)은 판정에 쓰이지 않는다 — 코드 조회 경로(MID 소속 → head.merchant_code)만 실제 운영 구현을 지난다.
 * 값은 Demo — 운영 가맹점 코드 아님.
 */
object MerchantCodeProjection {
    private val at: LocalDateTime = LocalDateTime.of(2026, 10, 14, 0, 0)

    /** [mid]의 head를 만든다. [code]가 있으면 v4 head(revision 1·코드), null이면 v3 head(코드 없음 — 승격 전). 만든 가맹점 내부 키를 돌려준다. */
    fun project(db: DbAccess, mid: String, code: String?): String {
        val merchantId = "mer_" + UUID.randomUUID().toString().replace("-", "").substring(0, 26).uppercase()
        db.withRoot { c ->
            with(db) {
                c.catalog = db.databaseName
                c.update(
                    "INSERT INTO entitlement_head (merchant_id, revision, content_hash, schema_version, issued_at, lease_seq, lease_until, applied_at, version, merchant_code) " +
                        "VALUES (?, 1, ?, ?, ?, 0, ?, ?, 0, ?)",
                    merchantId, "ec1:" + "0".repeat(64), if (code == null) 3 else 4, at, at.plusHours(1), at, code,
                )
                c.update("INSERT INTO entitlement_mid_owner (merchant_account_id, merchant_id, recorded_at) VALUES (?, ?, ?)", mid, merchantId, at)
            }
        }
        return merchantId
    }

    /**
     * v3 → v4 승격 적용(revision + 1, 코드 생김)의 저장 결과를 흉내 낸다. 같은 head에 다시 부르면 "MID 있는 가맹점의 코드가 바뀐" 상태가 된다 — 정상 경로에서 불가능한
     * **Invalid Fixture**(MASTER 잠금·승인 사실·PAYMENT 경보가 막는 상태)이며, 저장된 재생 결과가 응답 시점 값에 끌려가지 않는지 보는 용도로만 쓴다.
     */
    fun promote(db: DbAccess, merchantId: String, code: String) {
        db.withRoot { c ->
            with(db) {
                c.catalog = db.databaseName
                check(
                    c.update(
                        "UPDATE entitlement_head SET revision = revision + 1, schema_version = 4, merchant_code = ?, version = version + 1 WHERE merchant_id = ?", code, merchantId,
                    ) == 1,
                ) { "the projected head exists" }
            }
        }
    }
}
