package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.CARD_ROUTE_ACTIVATION
import com.paycore.master.jooq.generated.Tables.CONTRACT
import com.paycore.master.jooq.generated.Tables.CONTRACT_SERVICE_AGREEMENT
import com.paycore.master.jooq.generated.Tables.CURRENCY_ENABLEMENT
import com.paycore.master.jooq.generated.Tables.MASTER_CHANGE_HISTORY
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT_SITE_ASSIGNMENT
import com.paycore.master.jooq.generated.Tables.MERCHANT_SITE
import com.paycore.master.jooq.generated.Tables.PAYMENT_METHOD_ACTIVATION
import com.paycore.master.jooq.generated.Tables.POLICY_ASSIGNMENT
import com.paycore.master.jooq.generated.Tables.SITE_ACTIVATION_CONFIRMATION
import com.paycore.master.jooq.generated.Tables.TRANSACTION_LIMIT_POLICY
import com.paycore.master.merchant.application.provided.MerchantHistoryEntry
import com.paycore.master.merchant.application.provided.MerchantHistoryPage
import com.paycore.master.merchant.application.required.MerchantHistoryReader
import com.paycore.master.merchant.domain.MerchantId
import org.jooq.DSLContext
import org.jooq.Record1
import org.jooq.Row2
import org.jooq.SelectConditionStep
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 가맹점 변경 이력(MERCHANT-OPS-001 §3.3, jOOQ 읽기 전용). 한 읽기 전용 TX에서 가맹점 소유 집계 ID를 모으고 `(aggregate_type, aggregate_id) IN (…)`(기존 색인
 * `ix_master_change_history_aggregate`) + `id > afterSeq` 오름차순으로 한 쪽을 읽는다. **`detail`(기관 가맹점 번호 평문 포함 가능 — D-MS2-005)·`request_record_id`는
 * SELECT하지 않는다.** 집계 종류 이름은 application 명령 서비스가 기록하는 값과 같다(AGG_* 상수). 전역 PolicyVersion은 가맹점 소유가 아니라 제외한다.
 */
@Component
class JooqMerchantHistoryReader(private val dsl: DSLContext) : MerchantHistoryReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun page(merchantId: MerchantId, afterSeq: Long): MerchantHistoryPage? {
        val id = merchantId.value
        val businessId = dsl.select(MERCHANT.BUSINESS_ID).from(MERCHANT).where(MERCHANT.MERCHANT_ID.eq(id)).fetchOne()?.value1() ?: return null
        val accountIds = DSL.select(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID).from(MERCHANT_ACCOUNT).where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(id))
        val owned: List<Pair<String, SelectConditionStep<Record1<String>>>> = listOf(
            "MerchantSite" to dsl.select(MERCHANT_SITE.MERCHANT_SITE_ID).from(MERCHANT_SITE).where(MERCHANT_SITE.MERCHANT_ID.eq(id)),
            "Contract" to dsl.select(CONTRACT.CONTRACT_ID).from(CONTRACT).where(CONTRACT.MERCHANT_ID.eq(id)),
            "ContractServiceAgreement" to dsl.select(CONTRACT_SERVICE_AGREEMENT.AGREEMENT_ID).from(CONTRACT_SERVICE_AGREEMENT)
                .join(CONTRACT).on(CONTRACT.CONTRACT_ID.eq(CONTRACT_SERVICE_AGREEMENT.CONTRACT_ID)).where(CONTRACT.MERCHANT_ID.eq(id)),
            "MerchantAccount" to dsl.select(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID).from(MERCHANT_ACCOUNT).where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(id)),
            "SiteAssignment" to dsl.select(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ASSIGNMENT_ID).from(MERCHANT_ACCOUNT_SITE_ASSIGNMENT)
                .where(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ID.eq(id)),
            "PaymentMethodActivation" to dsl.select(PAYMENT_METHOD_ACTIVATION.ACTIVATION_ID).from(PAYMENT_METHOD_ACTIVATION)
                .where(PAYMENT_METHOD_ACTIVATION.MERCHANT_ACCOUNT_ID.`in`(accountIds)),
            "SiteActivationConfirmation" to dsl.select(SITE_ACTIVATION_CONFIRMATION.CONFIRMATION_ID).from(SITE_ACTIVATION_CONFIRMATION)
                .where(SITE_ACTIVATION_CONFIRMATION.MERCHANT_ID.eq(id)),
            "CurrencyEnablement" to dsl.select(CURRENCY_ENABLEMENT.ENABLEMENT_ID).from(CURRENCY_ENABLEMENT)
                .where(CURRENCY_ENABLEMENT.MERCHANT_ACCOUNT_ID.`in`(accountIds)),
            "PolicyAssignment" to dsl.select(POLICY_ASSIGNMENT.ASSIGNMENT_ID).from(POLICY_ASSIGNMENT).where(POLICY_ASSIGNMENT.MERCHANT_ID.eq(id)),
            "TransactionLimitPolicy" to dsl.select(TRANSACTION_LIMIT_POLICY.POLICY_ID).from(TRANSACTION_LIMIT_POLICY).where(TRANSACTION_LIMIT_POLICY.MERCHANT_ID.eq(id)),
            "CardRouteActivation" to dsl.select(CARD_ROUTE_ACTIVATION.ACTIVATION_ID).from(CARD_ROUTE_ACTIVATION).where(CARD_ROUTE_ACTIVATION.MERCHANT_ID.eq(id)),
        )
        val keys: List<Row2<String, String>> = listOf(DSL.row(BUSINESS, businessId), DSL.row(MERCHANT_TYPE, id)) +
            owned.flatMap { (type, ids) -> ids.fetch { it.value1() }.map { DSL.row(type, it) } }

        val h = MASTER_CHANGE_HISTORY
        val rows = dsl.select(h.ID, MasterSqlText.instant(h.OCCURRED_AT), h.OPERATION, h.AGGREGATE_TYPE, h.AGGREGATE_ID, h.CHANGE_TYPE, h.ACTOR_REF)
            .from(h)
            .where(DSL.row(h.AGGREGATE_TYPE, h.AGGREGATE_ID).`in`(keys))
            .and(h.ID.gt(afterSeq))
            .orderBy(h.ID)
            .limit(MerchantHistoryPage.PAGE_SIZE + 1)
            .fetch { MerchantHistoryEntry(it.value1(), Instant.parse(it.value2()), it.value3(), it.value4(), it.value5(), it.value6(), it.value7()) }
        val page = rows.take(MerchantHistoryPage.PAGE_SIZE)
        return MerchantHistoryPage(page, if (rows.size > MerchantHistoryPage.PAGE_SIZE) page.last().seq else null)
    }

    private companion object {
        const val BUSINESS = "Business"
        const val MERCHANT_TYPE = "Merchant"
    }
}
