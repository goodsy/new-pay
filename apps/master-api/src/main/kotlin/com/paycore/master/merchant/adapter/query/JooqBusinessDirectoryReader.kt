package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.BUSINESS_REPRESENTATIVE
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.merchant.application.provided.BusinessDirectoryItem
import com.paycore.master.merchant.application.provided.BusinessDirectoryPage
import com.paycore.master.merchant.application.provided.BusinessSearchFilter
import com.paycore.master.merchant.application.required.BusinessDirectoryReader
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 사업자 목록(BUSINESS-PROFILE-001 §6.2, jOOQ 읽기 전용 — CQRS). `business`에서 출발하므로 **가맹점 0개 사업자도 나온다**(D-BIZ-004 — 가맹점 검색의 `JOIN business`와 다름).
 * 정렬 `business_id` 내림차순(ULID — 최신 먼저, 유일·안정), keyset `business_id < after`, `limit + 1`행으로 다음 쪽 판정(C-28).
 *
 * SELECT 목록(시험이 SQL로 고정): 번호는 `LEFT(registration_number, 3)` 마스킹만, 대표자는 현재 첫 순서 이름의 SQL 마스킹(`BusinessPiiMask.name`과 같은 모양)·현재 수만,
 * 가맹점 수. **원문 번호·대표자 원문·연락처·주소·법인등록번호는 SELECT하지 않는다**(§9).
 * 상호 앞부분 일치는 `LIKE ? ESCAPE '!'`(`%`·`_`·`!`를 `!`로 이스케이프 — 입력이 와일드카드가 되지 않는다, C-5 — [MasterSqlText.likePrefix], 가맹점명 검색과 공유). 상호 접두·상태·기간 조건은 색인 범위를 읽은 뒤
 * `business_id` 내림차순으로 다시 정렬한다(filesort — L-11, LIVE Gate에서 EXPLAIN 기록).
 */
@Component
class JooqBusinessDirectoryReader(private val dsl: DSLContext) : BusinessDirectoryReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun search(filter: BusinessSearchFilter): BusinessDirectoryPage {
        val conditions = mutableListOf<Condition>()
        filter.displayNamePrefix?.let { conditions += BUSINESS.DISPLAY_NAME.like(MasterSqlText.likePrefix(it.value), MasterSqlText.ESCAPE) }
        filter.businessType?.let { conditions += BUSINESS.BUSINESS_TYPE.eq(it.name) }
        filter.registrationStatus?.let { conditions += BUSINESS.REGISTRATION_STATUS.eq(it.name) }
        filter.createdAtFrom?.let { conditions += BUSINESS.CREATED_AT.ge(MasterSqlText.utcLiteral(it)) }
        filter.createdAtBefore?.let { conditions += BUSINESS.CREATED_AT.lt(MasterSqlText.utcLiteral(it)) }
        filter.after?.let { conditions += BUSINESS.BUSINESS_ID.lt(it.value) }

        val rep = BUSINESS_REPRESENTATIVE
        val currentRep = rep.BUSINESS_ID.eq(BUSINESS.BUSINESS_ID).and(rep.EFFECTIVE_TO.isNull)
        val firstRepresentative = DSL.field(
            DSL.select(MasterSqlText.maskedPersonName(rep.NAME)).from(rep).where(currentRep).orderBy(rep.DISPLAY_ORDER).limit(1),
        )
        val representativeCount = DSL.field(DSL.selectCount().from(rep).where(currentRep))
        val merchantCount = DSL.field(DSL.selectCount().from(MERCHANT).where(MERCHANT.BUSINESS_ID.eq(BUSINESS.BUSINESS_ID)))

        val rows = dsl.select(
            BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.maskedRegistrationNumber(), BUSINESS.BUSINESS_TYPE, BUSINESS.REGISTRATION_STATUS,
            BUSINESS.VERIFICATION_STATUS, firstRepresentative, representativeCount, merchantCount, MasterSqlText.instant(BUSINESS.UPDATED_AT),
        )
            .from(BUSINESS)
            .where(conditions)
            .orderBy(BUSINESS.BUSINESS_ID.desc())
            .limit(filter.limit + 1)
            .fetch { r ->
                BusinessDirectoryItem(
                    businessId = r.value1(), businessName = r.value2(), registrationNumberMasked = r.value3(), businessType = r.value4(),
                    registrationStatus = r.value5(), verificationStatus = r.value6(), representativeNameMasked = r.value7(),
                    representativeCount = r.value8().toLong(), merchantCount = r.value9().toLong(), updatedAt = Instant.parse(r.value10()),
                )
            }
        val page = rows.take(filter.limit)
        return BusinessDirectoryPage(page, if (rows.size > filter.limit) page.last().businessId else null)
    }
}
