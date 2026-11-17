package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.merchant.application.provided.MerchantLabel
import com.paycore.master.merchant.application.required.MerchantLabelReader
import org.jooq.DSLContext
import org.jooq.Record3
import org.jooq.SelectSeekStep1
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/**
 * 가맹점 라벨 묶음(MERCHANT-CODE-001 §5.6.3 MC-3b, jOOQ 읽기 전용 REPEATABLE READ): **한 문장** — `merchant` PK `IN`(≤ 50, range) → 현재 코드
 * (`MasterSqlText.currentMerchantCode` — 배정 PK 범위 상관 부분 질의, MC-1b 변경 가맹점은 새 코드). merchantId는 바인드 값으로만 쓰고 로그·예외 문구에 넣지 않는다.
 * 사업자 키·사업자·lifecycle·등록번호 열은 SELECT에 없다.
 */
@Component
class JooqMerchantLabelReader(private val dsl: DSLContext) : MerchantLabelReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun labels(merchantIds: List<String>): List<MerchantLabel> = statement(merchantIds).fetch { r ->
        // 배정 없음 = 결함(등록 TX·V13 채우기가 보장) — 조용히 빈 값으로 내보내지 않는다(컨트롤러가 503으로 바꾼다).
        MerchantLabel(r.value1(), r.value2(), checkNotNull(r.value3()) { "merchant without a code assignment" })
    }

    /** 계획 시험(MC-3-17 P-1)이 같은 문장을 EXPLAIN 하도록 문장 생성만 따로 둔다(실행하지 않는다). */
    internal fun statement(merchantIds: List<String>): SelectSeekStep1<Record3<String, String, String?>, String> =
        dsl.select(MERCHANT.MERCHANT_ID, MERCHANT.DISPLAY_NAME, MasterSqlText.currentMerchantCode(MERCHANT.MERCHANT_ID))
            // 표가 작으면 optimizer가 보조 색인 전체 스캔을 고를 수 있다 — PK range를 강제한다(JooqMerchantAccountLabelReader T-E2-7과 같은 방식).
            .from(MERCHANT.forceIndex("PRIMARY"))
            .where(MERCHANT.MERCHANT_ID.`in`(merchantIds))
            .orderBy(MERCHANT.MERCHANT_ID)
}
