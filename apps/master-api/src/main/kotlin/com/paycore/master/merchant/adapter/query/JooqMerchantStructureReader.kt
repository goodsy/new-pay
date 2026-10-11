package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.DEFAULT_DESIGNATION
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT_SITE_ASSIGNMENT
import com.paycore.master.jooq.generated.Tables.MERCHANT_SITE
import com.paycore.master.jooq.generated.Tables.PAYMENT_METHOD_ACTIVATION
import com.paycore.master.merchant.application.provided.AccountView
import com.paycore.master.merchant.application.provided.AssignmentView
import com.paycore.master.merchant.application.provided.MerchantStructureView
import com.paycore.master.merchant.application.provided.SiteView
import com.paycore.master.merchant.application.required.MerchantStructureReader
import com.paycore.master.merchant.domain.MerchantId
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDateTime

/**
 * Merchant 구조 조회(CQRS 조회 측, jOOQ). 같은 MASTER DB의 소유 표만 읽고 DTO로 돌려준다(Entity 노출 없음, N+1 대신 표마다 한 번 조회).
 * 쓰기 API를 쓰지 않는다(ArchUnit queryAdapterDoesNotWriteWithJooq). 등록번호 컬럼은 읽지 않는다(D-MD-008).
 *
 * 시각: 생성 코드의 DATETIME 필드(`LocalDateTime`)를 그대로 읽으면 **JVM 기본 시간대 벽시계**가 된다 — driver(connectionTimeZone=UTC)가 값을
 * UTC 순간으로 해석한 Timestamp를 jOOQ가 JVM 시간대로 LocalDateTime 변환하기 때문이다(Asia/Seoul JVM에서 02:00Z가 11:00으로 읽힌 RED,
 * MerchantStructureQuerySpec). 그래서 시각 컬럼은 저장된 UTC 벽시계를 SQL `DATE_FORMAT`으로 ISO-8601 `…Z` 문자열로 읽어 `Instant.parse`한다:
 * driver·JVM·세션 시간대 변환이 끼지 않는다(저장 값 자체가 UTC — 관례 §9.1). `java.sql` 타입은 adapter.query에서 쓰지 않는다(ArchUnit 영속 기술 위치).
 * 판정용 권위 조회가 아니다(결제 허용은 PAYMENT의 Snapshot·승인 Use Case).
 *
 * 읽기 일관성(review m-2): 표마다 따로 읽는 6개 문장을 **하나의 읽기 전용 TX(REPEATABLE READ)** 에서 실행한다 — InnoDB가 첫 일관 읽기에서 만든
 * 스냅샷을 모든 문장이 공유하므로, 그 사이에 커밋된 발급(MID·Default·PRIMARY)이 일부 표에만 보이는 찢어진 구조를 돌려주지 않는다.
 * jOOQ는 Boot 구성의 트랜잭션 인식 DataSource로 Spring TX에 묶인 같은 연결을 쓴다(`MerchantStructureQuerySpec`이 문장별 TX 상태를 확인).
 */
@Component
class JooqMerchantStructureReader(private val dsl: DSLContext) : MerchantStructureReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun read(merchantId: MerchantId): MerchantStructureView? {
        val merchant = dsl.select(MERCHANT.MERCHANT_ID, MERCHANT.BUSINESS_ID, MERCHANT.LIFECYCLE, MERCHANT.ENTITLEMENT_REVISION)
            .from(MERCHANT)
            .where(MERCHANT.MERCHANT_ID.eq(merchantId.value))
            .fetchOne() ?: return null

        val sites = dsl.select(MERCHANT_SITE.MERCHANT_SITE_ID, MERCHANT_SITE.CHANNEL_TYPE, MERCHANT_SITE.CHANNEL_IDENTIFIER, MERCHANT_SITE.LIFECYCLE)
            .from(MERCHANT_SITE)
            .where(MERCHANT_SITE.MERCHANT_ID.eq(merchantId.value))
            .orderBy(MERCHANT_SITE.MERCHANT_SITE_ID)
            .fetch { SiteView(it.value1(), it.value2(), it.value3(), it.value4()) }

        val defaultAccount = dsl.select(DEFAULT_DESIGNATION.MERCHANT_ACCOUNT_ID)
            .from(DEFAULT_DESIGNATION)
            .where(DEFAULT_DESIGNATION.MERCHANT_ID.eq(merchantId.value))
            .fetchOne()?.value1()

        val assignments = dsl.select(
            MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ACCOUNT_ID, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ASSIGNMENT_ID,
            MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_SITE_ID, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ROLE, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.STATUS,
            instant(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_FROM), instant(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_TO),
        )
            .from(MERCHANT_ACCOUNT_SITE_ASSIGNMENT)
            .where(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ID.eq(merchantId.value))
            .orderBy(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_FROM, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ASSIGNMENT_ID)
            .fetch()
            .groupBy({ it.value1() }) { AssignmentView(it.value2(), it.value3(), it.value4(), it.value5(), Instant.parse(it.value6()), it.value7()?.let(Instant::parse)) }

        val methods = dsl.select(PAYMENT_METHOD_ACTIVATION.MERCHANT_ACCOUNT_ID, PAYMENT_METHOD_ACTIVATION.PAYMENT_METHOD)
            .from(PAYMENT_METHOD_ACTIVATION)
            .join(MERCHANT_ACCOUNT).on(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID.eq(PAYMENT_METHOD_ACTIVATION.MERCHANT_ACCOUNT_ID))
            .where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(merchantId.value))
            .orderBy(PAYMENT_METHOD_ACTIVATION.PAYMENT_METHOD)
            .fetch()
            .groupBy({ it.value1() }) { it.value2() }

        val accounts = dsl.select(
            MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID, MERCHANT_ACCOUNT.LIFECYCLE, MERCHANT_ACCOUNT.ISSUING_CONTRACT_ID,
            MERCHANT_ACCOUNT.ISSUING_AGREEMENT_ID, MERCHANT_ACCOUNT.ISSUING_REVISION_NO,
        )
            .from(MERCHANT_ACCOUNT)
            .where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(merchantId.value))
            .orderBy(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID)
            .fetch { r ->
                AccountView(
                    merchantAccountId = r.value1(),
                    lifecycle = r.value2(),
                    isDefault = r.value1() == defaultAccount,
                    issuingContractId = r.value3(),
                    issuingAgreementId = r.value4(),
                    issuingRevisionNo = r.value5(),
                    assignments = assignments[r.value1()].orEmpty(),
                    registeredPaymentMethods = methods[r.value1()].orEmpty(),
                )
            }

        return MerchantStructureView(
            merchantId = merchant.value1(),
            businessId = merchant.value2(),
            lifecycle = merchant.value3(),
            entitlementRevision = merchant.value4(),
            sites = sites,
            accounts = accounts,
        )
    }

    /** DATETIME(3) 컬럼의 저장 UTC 벽시계를 `2026-10-09T02:00:00.000000Z` 형식 문자열로 읽는다(위 KDoc). NULL은 NULL. */
    private fun instant(field: Field<LocalDateTime?>): Field<String?> =
        DSL.field("DATE_FORMAT({0}, '%Y-%m-%dT%H:%i:%s.%fZ')", String::class.java, field)
}
