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
 * 가맹점 목록(MERCHANT-OPS-001 §3.1, jOOQ 읽기 전용). merchant_id 오름차순 keyset(`> after`), `limit + 1`행으로 다음 쪽 판정. 조건은 가맹점명 접두 외에는 모두 정확 일치이며
 * 등록번호는 바인드 값으로만 쓰고(로그·결과에 없음) 결과의 등록번호는 원문(BRN-UNMASK-001 — D-BIZ-007: 조회 응답 원문 10자리, 응답 필드 `registrationNumber` — v2 계약). MID 수는 상관 부분 질의(PK·FK 색인).
 *
 * MERCHANT-NAME-SEARCH-001: 가맹점명 앞부분 일치는 `display_name LIKE ? ESCAPE '!'`(`%`·`_`·`!`를 `!`로 이스케이프 — [MasterSqlText.likePrefix], 사업자 상호 접두와 같은 규칙).
 * 색인 `ix_merchant_name_id (display_name, merchant_id)`(V17)로 접두 범위를 읽은 뒤 merchant_id 오름차순으로 다시 정렬한다(filesort — 알려진 한계, LIVE Gate에서 EXPLAIN 기록).
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
        filter.displayNamePrefix?.let { conditions += MERCHANT.DISPLAY_NAME.like(MasterSqlText.likePrefix(it.value), MasterSqlText.ESCAPE) }
        // MERCHANT-CODE-001 §3.8: 코드 검색 = 현재·은퇴 코드 정확 일치(배정 표 UNIQUE 색인 — 0 또는 1 가맹점).
        filter.merchantCode?.let { code ->
            conditions += MERCHANT.MERCHANT_ID.`in`(
                DSL.select(MERCHANT_CODE_ASSIGNMENT.MERCHANT_ID).from(MERCHANT_CODE_ASSIGNMENT).where(MERCHANT_CODE_ASSIGNMENT.MERCHANT_CODE.eq(code.value)),
            )
        }
        val midCount = DSL.field(DSL.selectCount().from(MERCHANT_ACCOUNT).where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(MERCHANT.MERCHANT_ID)))
        val currentCode = MasterSqlText.currentMerchantCode(MERCHANT.MERCHANT_ID)
        val rows = dsl.select(
            MERCHANT.MERCHANT_ID, MERCHANT.DISPLAY_NAME, BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.registrationNumber(),
            MERCHANT.LIFECYCLE, MERCHANT.ENTITLEMENT_REVISION, midCount, MasterSqlText.instant(MERCHANT.CREATED_AT), currentCode,
        )
            .from(MERCHANT)
            .join(BUSINESS).on(BUSINESS.BUSINESS_ID.eq(MERCHANT.BUSINESS_ID))
            .where(conditions)
            .orderBy(MERCHANT.MERCHANT_ID)
            .limit(filter.limit + 1)
            .fetch { r ->
                MerchantDirectoryItem(
                    merchantId = r.value1(), merchantName = r.value2(), businessId = r.value3(), businessName = r.value4(), registrationNumber = r.value5(),
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
