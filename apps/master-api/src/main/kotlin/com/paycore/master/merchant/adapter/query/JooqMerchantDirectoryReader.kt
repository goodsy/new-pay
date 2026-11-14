package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.jooq.generated.Tables.MERCHANT_CODE_ASSIGNMENT
import com.paycore.master.merchant.application.provided.MerchantDirectoryItem
import com.paycore.master.merchant.application.provided.MerchantDirectoryPage
import com.paycore.master.merchant.application.provided.MerchantSearchFilter
import com.paycore.master.merchant.application.required.MerchantDirectoryReader
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 가맹점 목록(MERCHANT-OPS-001 §3.1, jOOQ 읽기 전용). merchant_id 오름차순 keyset(`> after`), `limit + 1`행으로 다음 쪽 판정. 조건은 모두 정확 일치이며 등록번호는
 * 바인드 값으로만 쓰고(로그·결과에 없음) 결과에는 SQL 마스킹 값만 담는다. MID 수는 상관 부분 질의(PK·FK 색인).
 */
@Component
class JooqMerchantDirectoryReader(private val dsl: DSLContext) : MerchantDirectoryReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun search(filter: MerchantSearchFilter): MerchantDirectoryPage {
        val conditions = mutableListOf<Condition>()
        filter.merchantId?.let { conditions += MERCHANT.MERCHANT_ID.eq(it.value) }
        filter.businessId?.let { conditions += MERCHANT.BUSINESS_ID.eq(it.value) }
        filter.lifecycle?.let { conditions += MERCHANT.LIFECYCLE.eq(it.name) }
        filter.registrationNumber?.let { conditions += BUSINESS.REGISTRATION_NUMBER.eq(it.value) }
        filter.after?.let { conditions += MERCHANT.MERCHANT_ID.gt(it.value) }
        // MERCHANT-CODE-001 §3.8: 코드 검색 = 현재·은퇴 코드 정확 일치(배정 표 UNIQUE 색인 — 0 또는 1 가맹점).
        filter.merchantCode?.let { code ->
            conditions += MERCHANT.MERCHANT_ID.`in`(
                DSL.select(MERCHANT_CODE_ASSIGNMENT.MERCHANT_ID).from(MERCHANT_CODE_ASSIGNMENT).where(MERCHANT_CODE_ASSIGNMENT.MERCHANT_CODE.eq(code.value)),
            )
        }
        val midCount = DSL.field(DSL.selectCount().from(MERCHANT_ACCOUNT).where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(MERCHANT.MERCHANT_ID)))
        val currentCode = MasterSqlText.currentMerchantCode(MERCHANT.MERCHANT_ID)
        val rows = dsl.select(
            MERCHANT.MERCHANT_ID, MERCHANT.DISPLAY_NAME, BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.maskedRegistrationNumber(),
            MERCHANT.LIFECYCLE, MERCHANT.ENTITLEMENT_REVISION, midCount, MasterSqlText.instant(MERCHANT.CREATED_AT), currentCode,
        )
            .from(MERCHANT)
            .join(BUSINESS).on(BUSINESS.BUSINESS_ID.eq(MERCHANT.BUSINESS_ID))
            .where(conditions)
            .orderBy(MERCHANT.MERCHANT_ID)
            .limit(filter.limit + 1)
            .fetch { r ->
                MerchantDirectoryItem(
                    merchantId = r.value1(), merchantName = r.value2(), businessId = r.value3(), businessName = r.value4(), registrationNumberMasked = r.value5(),
                    lifecycle = r.value6(), entitlementRevision = r.value7(), midCount = r.value8().toLong(), createdAt = Instant.parse(r.value9()),
                    // 배정 없음 = 결함(등록 TX·V13 채우기가 보장) — 조용히 빈 값으로 내보내지 않는다.
                    merchantCode = checkNotNull(r.value10()) { "merchant without a code assignment" },
                    matchedRetiredCode = filter.merchantCode?.let { it.value != r.value10() },
                )
            }
        val page = rows.take(filter.limit)
        return MerchantDirectoryPage(page, if (rows.size > filter.limit) page.last().merchantId else null)
    }
}
