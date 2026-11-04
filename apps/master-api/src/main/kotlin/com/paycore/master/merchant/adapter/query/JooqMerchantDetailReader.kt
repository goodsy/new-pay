package com.paycore.master.merchant.adapter.query

import com.paycore.master.jooq.generated.Tables.BUSINESS
import com.paycore.master.jooq.generated.Tables.CARD_ROUTE_ACTIVATION
import com.paycore.master.jooq.generated.Tables.CONTRACT
import com.paycore.master.jooq.generated.Tables.CONTRACT_REVISION
import com.paycore.master.jooq.generated.Tables.CONTRACT_REVISION_APPROVAL
import com.paycore.master.jooq.generated.Tables.CONTRACT_REVISION_SCOPE_SITE
import com.paycore.master.jooq.generated.Tables.CONTRACT_SERVICE_AGREEMENT
import com.paycore.master.jooq.generated.Tables.CURRENCY_ENABLEMENT
import com.paycore.master.jooq.generated.Tables.DEFAULT_DESIGNATION
import com.paycore.master.jooq.generated.Tables.MERCHANT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT
import com.paycore.master.jooq.generated.Tables.MERCHANT_ACCOUNT_SITE_ASSIGNMENT
import com.paycore.master.jooq.generated.Tables.MERCHANT_SITE
import com.paycore.master.jooq.generated.Tables.MERCHANT_SITE_REVIEW_DECISION
import com.paycore.master.jooq.generated.Tables.PAYMENT_METHOD_ACTIVATION
import com.paycore.master.jooq.generated.Tables.TRANSACTION_LIMIT_POLICY
import com.paycore.master.merchant.application.provided.AssignmentView
import com.paycore.master.merchant.application.provided.MerchantDetailView
import com.paycore.master.merchant.application.provided.MerchantDetailView.AccountDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.AgreementDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.CardRouteDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.ContractDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.CurrencyDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.LimitDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.PaymentMethodDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.RevisionDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.SiteDetail
import com.paycore.master.merchant.application.provided.MerchantDetailView.SiteReview
import com.paycore.master.merchant.application.required.MerchantDetailReader
import com.paycore.master.merchant.domain.MerchantId
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 가맹점 상세(MERCHANT-OPS-001 §3.2, jOOQ 읽기 전용). 표마다 한 문장(N+1 없음), **모든 문장이 한 읽기 전용 REPEATABLE READ TX** — InnoDB 첫 일관 읽기의 스냅샷을 공유해
 * 그 사이 커밋된 발급·개통·Route가 일부 표에만 보이는 찢어진 상세를 돌려주지 않는다(JooqMerchantStructureReader review m-2와 같은 이유).
 *
 * 비노출(D-MD-008, D-MS2-005): 등록번호는 SQL 마스킹 값만 읽고, `card_route_activation.institution_merchant_ref`·`evidence_ref`·`reason` 열은 SELECT 목록에 없다.
 * 시각은 [MasterSqlText.instant](저장 UTC 벽시계 문자열 — JVM 시간대 무관). 전파 상태는 application이 별도 Port로 채운다(여기서는 null).
 */
@Component
class JooqMerchantDetailReader(private val dsl: DSLContext) : MerchantDetailReader {

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    override fun read(merchantId: MerchantId): MerchantDetailView? {
        val id = merchantId.value
        val head = dsl.select(
            MERCHANT.MERCHANT_ID, MERCHANT.DISPLAY_NAME, MERCHANT.LIFECYCLE, MERCHANT.ENTITLEMENT_REVISION, MasterSqlText.instant(MERCHANT.CREATED_AT),
            BUSINESS.BUSINESS_ID, BUSINESS.DISPLAY_NAME, MasterSqlText.maskedRegistrationNumber(), BUSINESS.LIFECYCLE,
        )
            .from(MERCHANT)
            .join(BUSINESS).on(BUSINESS.BUSINESS_ID.eq(MERCHANT.BUSINESS_ID))
            .where(MERCHANT.MERCHANT_ID.eq(id))
            .fetchOne() ?: return null

        return MerchantDetailView(
            merchant = MerchantDetailView.MerchantSummary(head.value1(), head.value2(), head.value3(), head.value4(), Instant.parse(head.value5())),
            business = MerchantDetailView.BusinessSummary(head.value6(), head.value7(), head.value8(), head.value9()),
            sites = sites(id),
            contracts = contracts(id),
            accounts = accounts(id),
            limits = limits(id),
            propagation = null,
        )
    }

    private fun sites(merchantId: String): List<SiteDetail> {
        // 최신 심사 결정(사이트별 최대 seq) — 오래된 순으로 읽어 마지막 값이 남게 한다.
        val latestReview = dsl.select(
            MERCHANT_SITE_REVIEW_DECISION.MERCHANT_SITE_ID, MERCHANT_SITE_REVIEW_DECISION.DECISION, MasterSqlText.instant(MERCHANT_SITE_REVIEW_DECISION.DECIDED_AT),
        )
            .from(MERCHANT_SITE_REVIEW_DECISION)
            .where(MERCHANT_SITE_REVIEW_DECISION.MERCHANT_ID.eq(merchantId))
            .orderBy(MERCHANT_SITE_REVIEW_DECISION.MERCHANT_SITE_ID, MERCHANT_SITE_REVIEW_DECISION.SEQ)
            .fetch()
            .associate { it.value1() to SiteReview(it.value2(), Instant.parse(it.value3())) }
        return dsl.select(MERCHANT_SITE.MERCHANT_SITE_ID, MERCHANT_SITE.CHANNEL_TYPE, MERCHANT_SITE.CHANNEL_IDENTIFIER, MERCHANT_SITE.LIFECYCLE)
            .from(MERCHANT_SITE)
            .where(MERCHANT_SITE.MERCHANT_ID.eq(merchantId))
            .orderBy(MERCHANT_SITE.MERCHANT_SITE_ID)
            .fetch { SiteDetail(it.value1(), it.value2(), it.value3(), it.value4(), latestReview[it.value1()]) }
    }

    private fun contracts(merchantId: String): List<ContractDetail> {
        val scopeSites = dsl.select(CONTRACT_REVISION_SCOPE_SITE.CONTRACT_ID, CONTRACT_REVISION_SCOPE_SITE.REVISION_NO, CONTRACT_REVISION_SCOPE_SITE.MERCHANT_SITE_ID)
            .from(CONTRACT_REVISION_SCOPE_SITE)
            .where(CONTRACT_REVISION_SCOPE_SITE.MERCHANT_ID.eq(merchantId))
            .orderBy(CONTRACT_REVISION_SCOPE_SITE.MERCHANT_SITE_ID)
            .fetch()
            .groupBy({ it.value1() to it.value2() }) { it.value3() }
        val revisions = dsl.select(
            CONTRACT_REVISION.CONTRACT_ID, CONTRACT_REVISION.REVISION_NO, CONTRACT_REVISION.SCOPE_KIND, MasterSqlText.instant(CONTRACT_REVISION.EFFECTIVE_FROM),
            MasterSqlText.instant(CONTRACT_REVISION.EFFECTIVE_TO), MasterSqlText.instant(CONTRACT_REVISION_APPROVAL.APPROVED_AT),
        )
            .from(CONTRACT_REVISION)
            .leftJoin(CONTRACT_REVISION_APPROVAL).on(
                CONTRACT_REVISION_APPROVAL.CONTRACT_ID.eq(CONTRACT_REVISION.CONTRACT_ID).and(CONTRACT_REVISION_APPROVAL.REVISION_NO.eq(CONTRACT_REVISION.REVISION_NO)),
            )
            .where(CONTRACT_REVISION.SCOPE_MERCHANT_ID.eq(merchantId))
            .orderBy(CONTRACT_REVISION.CONTRACT_ID, CONTRACT_REVISION.REVISION_NO)
            .fetch()
            .groupBy({ it.value1() }) {
                RevisionDetail(
                    revisionNo = it.value2(), scopeKind = it.value3(), effectiveFrom = Instant.parse(it.value4()), effectiveTo = it.value5()?.let(Instant::parse),
                    approvedAt = it.value6()?.let(Instant::parse), scopeSiteIds = scopeSites[it.value1() to it.value2()].orEmpty(),
                )
            }
        val agreements = dsl.select(
            CONTRACT_SERVICE_AGREEMENT.CONTRACT_ID, CONTRACT_SERVICE_AGREEMENT.AGREEMENT_ID, CONTRACT_SERVICE_AGREEMENT.SERVICE_KIND,
            MasterSqlText.instant(CONTRACT_SERVICE_AGREEMENT.ADDED_AT),
        )
            .from(CONTRACT_SERVICE_AGREEMENT)
            .join(CONTRACT).on(CONTRACT.CONTRACT_ID.eq(CONTRACT_SERVICE_AGREEMENT.CONTRACT_ID))
            .where(CONTRACT.MERCHANT_ID.eq(merchantId))
            .orderBy(CONTRACT_SERVICE_AGREEMENT.AGREEMENT_ID)
            .fetch()
            .groupBy({ it.value1() }) { AgreementDetail(it.value2(), it.value3(), Instant.parse(it.value4())) }
        return dsl.select(CONTRACT.CONTRACT_ID, MasterSqlText.instant(CONTRACT.CREATED_AT))
            .from(CONTRACT)
            .where(CONTRACT.MERCHANT_ID.eq(merchantId))
            .orderBy(CONTRACT.CONTRACT_ID)
            .fetch { ContractDetail(it.value1(), Instant.parse(it.value2()), revisions[it.value1()].orEmpty(), agreements[it.value1()].orEmpty()) }
    }

    private fun accounts(merchantId: String): List<AccountDetail> {
        val defaultAccount = dsl.select(DEFAULT_DESIGNATION.MERCHANT_ACCOUNT_ID)
            .from(DEFAULT_DESIGNATION)
            .where(DEFAULT_DESIGNATION.MERCHANT_ID.eq(merchantId))
            .fetchOne()?.value1()
        val assignments = dsl.select(
            MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ACCOUNT_ID, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ASSIGNMENT_ID, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_SITE_ID,
            MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ROLE, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.STATUS,
            MasterSqlText.instant(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_FROM), MasterSqlText.instant(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_TO),
        )
            .from(MERCHANT_ACCOUNT_SITE_ASSIGNMENT)
            .where(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.MERCHANT_ID.eq(merchantId))
            .orderBy(MERCHANT_ACCOUNT_SITE_ASSIGNMENT.EFFECTIVE_FROM, MERCHANT_ACCOUNT_SITE_ASSIGNMENT.ASSIGNMENT_ID)
            .fetch()
            .groupBy({ it.value1() }) { AssignmentView(it.value2(), it.value3(), it.value4(), it.value5(), Instant.parse(it.value6()), it.value7()?.let(Instant::parse)) }
        val methods = dsl.select(
            PAYMENT_METHOD_ACTIVATION.MERCHANT_ACCOUNT_ID, PAYMENT_METHOD_ACTIVATION.ACTIVATION_ID, PAYMENT_METHOD_ACTIVATION.PAYMENT_METHOD,
            PAYMENT_METHOD_ACTIVATION.STATUS, MasterSqlText.instant(PAYMENT_METHOD_ACTIVATION.REGISTERED_AT),
        )
            .from(PAYMENT_METHOD_ACTIVATION)
            .join(MERCHANT_ACCOUNT).on(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID.eq(PAYMENT_METHOD_ACTIVATION.MERCHANT_ACCOUNT_ID))
            .where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(merchantId))
            .orderBy(PAYMENT_METHOD_ACTIVATION.PAYMENT_METHOD, PAYMENT_METHOD_ACTIVATION.ACTIVATION_ID)
            .fetch()
            .groupBy({ it.value1() }) { PaymentMethodDetail(it.value2(), it.value3(), it.value4(), Instant.parse(it.value5())) }
        val currencies = dsl.select(
            CURRENCY_ENABLEMENT.MERCHANT_ACCOUNT_ID, CURRENCY_ENABLEMENT.ENABLEMENT_ID, CURRENCY_ENABLEMENT.CURRENCY_CODE,
            MasterSqlText.instant(CURRENCY_ENABLEMENT.EFFECTIVE_FROM), MasterSqlText.instant(CURRENCY_ENABLEMENT.EFFECTIVE_TO), MasterSqlText.instant(CURRENCY_ENABLEMENT.ENDED_AT),
        )
            .from(CURRENCY_ENABLEMENT)
            .join(MERCHANT_ACCOUNT).on(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID.eq(CURRENCY_ENABLEMENT.MERCHANT_ACCOUNT_ID))
            .where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(merchantId))
            .orderBy(CURRENCY_ENABLEMENT.EFFECTIVE_FROM, CURRENCY_ENABLEMENT.ENABLEMENT_ID)
            .fetch()
            .groupBy({ it.value1() }) {
                CurrencyDetail(it.value2(), it.value3(), Instant.parse(it.value4()), it.value5()?.let(Instant::parse), it.value6()?.let(Instant::parse))
            }
        // 기관 가맹점 번호(institution_merchant_ref)는 SELECT하지 않는다(D-MS2-005).
        val routes = dsl.select(
            CARD_ROUTE_ACTIVATION.MERCHANT_ACCOUNT_ID, CARD_ROUTE_ACTIVATION.ACTIVATION_ID, CARD_ROUTE_ACTIVATION.PAYMENT_METHOD, CARD_ROUTE_ACTIVATION.ACQUIRER_CODE,
            CARD_ROUTE_ACTIVATION.ROUTE_REF, CARD_ROUTE_ACTIVATION.STATUS, MasterSqlText.instant(CARD_ROUTE_ACTIVATION.REGISTERED_AT),
        )
            .from(CARD_ROUTE_ACTIVATION)
            .where(CARD_ROUTE_ACTIVATION.MERCHANT_ID.eq(merchantId))
            .orderBy(CARD_ROUTE_ACTIVATION.REGISTERED_AT, CARD_ROUTE_ACTIVATION.ACTIVATION_ID)
            .fetch()
            .groupBy({ it.value1() }) { CardRouteDetail(it.value2(), it.value3(), it.value4(), it.value5(), it.value6(), Instant.parse(it.value7())) }
        return dsl.select(
            MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID, MERCHANT_ACCOUNT.LIFECYCLE, MERCHANT_ACCOUNT.ISSUING_CONTRACT_ID, MERCHANT_ACCOUNT.ISSUING_AGREEMENT_ID,
            MERCHANT_ACCOUNT.ISSUING_REVISION_NO, MasterSqlText.instant(MERCHANT_ACCOUNT.ISSUED_AT),
        )
            .from(MERCHANT_ACCOUNT)
            .where(MERCHANT_ACCOUNT.MERCHANT_ID.eq(merchantId))
            .orderBy(MERCHANT_ACCOUNT.MERCHANT_ACCOUNT_ID)
            .fetch { r ->
                val mid = r.value1()
                AccountDetail(
                    merchantAccountId = mid, lifecycle = r.value2(), isDefault = mid == defaultAccount, issuingContractId = r.value3(),
                    issuingAgreementId = r.value4(), issuingRevisionNo = r.value5(), issuedAt = Instant.parse(r.value6()),
                    assignments = assignments[mid].orEmpty(), paymentMethods = methods[mid].orEmpty(), currencies = currencies[mid].orEmpty(),
                    cardRoutes = routes[mid].orEmpty(),
                )
            }
    }

    private fun limits(merchantId: String): List<LimitDetail> = dsl.select(
        TRANSACTION_LIMIT_POLICY.POLICY_ID, TRANSACTION_LIMIT_POLICY.SCOPE_KIND, TRANSACTION_LIMIT_POLICY.AGREEMENT_ID, TRANSACTION_LIMIT_POLICY.MERCHANT_ACCOUNT_ID,
        TRANSACTION_LIMIT_POLICY.CURRENCY_CODE, TRANSACTION_LIMIT_POLICY.PER_TX_KIND, TRANSACTION_LIMIT_POLICY.PER_TX_AMOUNT, TRANSACTION_LIMIT_POLICY.DAILY_KIND,
        TRANSACTION_LIMIT_POLICY.DAILY_AMOUNT, MasterSqlText.instant(TRANSACTION_LIMIT_POLICY.EFFECTIVE_FROM), MasterSqlText.instant(TRANSACTION_LIMIT_POLICY.EFFECTIVE_TO),
        MasterSqlText.instant(TRANSACTION_LIMIT_POLICY.ENDED_AT),
    )
        .from(TRANSACTION_LIMIT_POLICY)
        .where(TRANSACTION_LIMIT_POLICY.MERCHANT_ID.eq(merchantId))
        .orderBy(TRANSACTION_LIMIT_POLICY.EFFECTIVE_FROM, TRANSACTION_LIMIT_POLICY.POLICY_ID)
        .fetch {
            LimitDetail(
                policyId = it.value1(), scopeKind = it.value2(), agreementId = it.value3(), merchantAccountId = it.value4(), currencyCode = it.value5(),
                perTxKind = it.value6(), perTxAmount = it.value7(), dailyKind = it.value8(), dailyAmount = it.value9(), effectiveFrom = Instant.parse(it.value10()),
                effectiveTo = it.value11()?.let(Instant::parse), endedAt = it.value12()?.let(Instant::parse),
            )
        }
}
