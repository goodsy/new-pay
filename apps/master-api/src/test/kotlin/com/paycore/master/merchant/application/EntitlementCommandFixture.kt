package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.DecisionReason
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EvidenceRef
import com.paycore.master.merchant.domain.MerchantAccountId
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteReviewStatus
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * 권리 입력 명령의 Named Fixture(src/test 전용). 준비 데이터는 모두 **공식 use case**로 만든다(행 직접 삽입 없음). 근거 참조·사유는 시험 값이다.
 */
object EntitlementCommandFixture {
    val evidence = EvidenceRef("van-reply:test-0001")
    val newEvidence = EvidenceRef("van-reply:test-0002")
    val reason = DecisionReason("test reason")
    private val mapper = JsonMapper.builder().build()

    /** 결제 가능한 상태까지 만든 Merchant: MID 발급 → CARD 개통 등록 → 승인(PRIMARY 확인) → Site 심사 APPROVED → KRW 활성화. */
    data class Entitled(
        val ready: OnboardingFixture.ReadyMerchant,
        val mid: MerchantAccountId,
        val activationId: PaymentMethodActivationId,
    ) {
        val merchantId: MerchantId get() = ready.merchantId
        val siteId: MerchantSiteId get() = ready.siteId
    }

    fun issuedWithActivation(onboarding: MerchantOnboardingUseCase): Pair<OnboardingFixture.ReadyMerchant, Pair<MerchantAccountId, PaymentMethodActivationId>> {
        val ready = OnboardingFixture.readyMerchant(onboarding)
        val mid = MerchantAccountId(succeeded(onboarding.issueFirstDefaultMerchantAccount(ready.issueCommand())))
        val activation = PaymentMethodActivationId(
            succeeded(onboarding.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), mid, PaymentMethod.CARD))),
        )
        return ready to (mid to activation)
    }

    fun entitled(onboarding: MerchantOnboardingUseCase, entitlement: MerchantEntitlementUseCase): Entitled {
        val (ready, ids) = issuedWithActivation(onboarding)
        val (mid, activation) = ids
        succeeded(entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(seedRequest(), activation, evidence)))
        succeeded(entitlement.decideSiteReview(DecideSiteReviewCommand(seedRequest(), ready.siteId, SiteReviewStatus.APPROVED, reason, null)))
        succeeded(
            entitlement.enableCurrency(
                EnableCurrencyCommand(request(), mid, CurrencyCode("KRW"), EffectivePeriod(OnboardingFixture.t0), evidence),
            ),
        )
        return Entitled(ready, mid, activation)
    }

    /** 발행 기록 한 행(runtime 계정으로 읽는다). */
    data class IssueRow(val revision: Long, val leaseSeq: Long, val priority: String, val contentHash: String, val eventId: String)

    fun issuesOf(container: MySQLContainer, merchantId: MerchantId): List<IssueRow> = runtime(container).use { c ->
        c.prepareStatement(
            "SELECT revision, lease_seq, priority, content_hash, outbox_event_id FROM entitlement_snapshot_issue WHERE merchant_id = ? " +
                "ORDER BY revision, lease_seq",
        ).use { ps ->
            ps.setString(1, merchantId.value)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(IssueRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getString(5))) }
            }
        }
    }

    fun revisionOf(container: MySQLContainer, merchantId: MerchantId): Long = runtime(container).use { c ->
        c.prepareStatement("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?").use { ps ->
            ps.setString(1, merchantId.value)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    /** 가장 최근 Snapshot Outbox payload(JSON)와 그 행의 상태. */
    data class SnapshotOutbox(val payload: JsonNode, val publishStatus: String, val priority: Int, val attemptCount: Int, val lastFailure: String?)

    fun snapshotOutbox(container: MySQLContainer, eventId: String): SnapshotOutbox = runtime(container).use { c ->
        c.prepareStatement(
            "SELECT payload, publish_status, priority, attempt_count, last_failure_code FROM outbox_event WHERE event_id = ? " +
                "AND event_category = 'ENTITLEMENT_SNAPSHOT'",
        ).use { ps ->
            ps.setString(1, eventId)
            ps.executeQuery().use { rs ->
                check(rs.next()) { "snapshot outbox row must exist" }
                SnapshotOutbox(mapper.readTree(rs.getString(1)), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getString(5))
            }
        }
    }

    fun latestSnapshot(container: MySQLContainer, merchantId: MerchantId): JsonNode =
        snapshotOutbox(container, issuesOf(container, merchantId).last().eventId).payload

    fun ackCount(container: MySQLContainer, eventId: String): Long = runtime(container).use { c ->
        c.prepareStatement("SELECT COUNT(*) FROM entitlement_snapshot_ack WHERE outbox_event_id = ?").use { ps ->
            ps.setString(1, eventId)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }
}
