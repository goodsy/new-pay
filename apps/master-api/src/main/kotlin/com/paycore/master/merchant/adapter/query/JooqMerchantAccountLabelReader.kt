package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.merchant.application.provided.MerchantAccountLabel
import com.paycore.master.merchant.application.required.MerchantAccountLabelReader
import org.jooq.DSLContext
import org.jooq.Record3
import org.jooq.SelectSeekStep1
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * MID 라벨 묶음(TXN-LIST-ENRICH-001 §4.2·§9 P-1, jOOQ 읽기 전용 REPEATABLE READ): **한 문장** — `merchant_account` PK `IN`(≤ 50, range) → `merchant` PK(eq_ref) → 현재 코드
 * (`MasterSqlText.currentMerchantCode` — 배정 PK 범위 상관 부분 질의). MID는 바인드 값으로만 쓰고 로그·예외 문구에 넣지 않는다. 내부 키(`merchant_id`)·사업자·lifecycle 열은 SELECT에 없다.
 */
@Component
class JooqMerchantAccountLabelReader(private val dsl: DSLContext) : MerchantAccountLabelReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun labels(merchantAccountIds: List<String>): List<MerchantAccountLabel> = statement(merchantAccountIds).fetch { r ->
        // 배정 없음 = 결함(등록 TX·V13 채우기가 보장) — 조용히 빈 값으로 내보내지 않는다(컨트롤러가 503으로 바꾼다).
        MerchantAccountLabel(r.value1(), r.value2(), checkNotNull(r.value3()) { "merchant without a code assignment" })
    }

    /** 계획 시험(T-E2-7 P-1)이 같은 문장을 EXPLAIN 하도록 문장 생성만 따로 둔다(실행하지 않는다). */
    internal fun statement(merchantAccountIds: List<String>): SelectSeekStep1<Record3<String, String, String?>, String> =
        dsl.select(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID, MERCHANT.DISPLAY_NAME, MasterSqlText.currentMerchantCode(MERCHANT.MERCHANT_ID))
            // T-E2-7 RED(2026-11-16): 표가 작으면 optimizer가 `uk_merchant_account_merchant` 전체 색인 스캔(type=index)을 고른다 — PK range를 강제한다(PAYMENT TXN-OPS-001과 같은 방식).
            .from(MERCHANT_ACCOUNT.forceIndex("PRIMARY"))
            .join(MERCHANT).on(MERCHANT.MERCHANT_ID.eq(MERCHANT_ACCOUNT.MERCHANT_ID))
            .where(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID.`in`(merchantAccountIds))
            .orderBy(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID)
}
