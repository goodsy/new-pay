package com.paycore.master.merchant.application

import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.EntitlementCommandFixture.evidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.EntitlementCommandFixture.newEvidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.EntitlementCommandFixture.revisionOf
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.seedRequest
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.ApprovePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ConfirmSiteActivationCommand
import com.paycore.master.merchant.application.provided.DecideSiteReviewCommand
import com.paycore.master.merchant.application.provided.EnableCurrencyCommand
import com.paycore.master.merchant.application.provided.EndCurrencyEnablementCommand
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterContractRevisionCommand
import com.paycore.master.merchant.application.provided.RegisterPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.ResumePaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.RevokeSiteActivationConfirmationCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.application.provided.SuspendPaymentMethodActivationCommand
import com.paycore.master.merchant.application.provided.TerminatePaymentMethodActivationCommand
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.CurrencyEnablementId
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.EntitlementSnapshot
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.PaymentMethod
import com.paycore.master.merchant.domain.PaymentMethodActivationId
import com.paycore.master.merchant.domain.SiteActivationConfirmationId
import com.paycore.master.merchant.domain.SiteReviewStatus
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import tools.jackson.databind.JsonNode

/**
 * 권리 입력 명령(MASTER-SNAPSHOT-001)을 실제 MySQL 8.4.11(runtime 계정)에서 확인한다: 각 명령은 같은 MASTER TX에서 업무 변경·이력·
 * entitlement_revision +1·Snapshot 발행(issue lease_seq 0, priority HIGH + Outbox ENTITLEMENT_SNAPSHOT)을 함께 커밋하고, 거절은 아무것도 바꾸지
 * 않는다(revision·issue 그대로). Snapshot 내용은 MASTER 원본 상태와 같다(조립 golden 대조).
 */
@MasterDbTest
class MerchantEntitlementServiceSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
) : BehaviorSpec({

    afterEach { clock.reset() }

    fun scalar(sql: String, vararg args: Any?): Any? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getObject(1) else null }
        }
    }

    fun account(snapshot: JsonNode): JsonNode = snapshot.get("accounts").single()

    Given("a merchant prepared through the official commands") {
        Then("MID issuance, activation registration, approval, site review and currency enablement each advance the revision by one and publish one HIGH snapshot") {
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val (mid, activation) = ids
            val afterRegistration = revisionOf(container, ready.merchantId)
            issuesOf(container, ready.merchantId).map { it.revision to it.priority } shouldBe
                listOf(afterRegistration - 1 to "HIGH", afterRegistration to "HIGH")

            succeeded(entitlement.approvePaymentMethodActivation(ApprovePaymentMethodActivationCommand(seedRequest(), activation, evidence)))
            succeeded(entitlement.decideSiteReview(DecideSiteReviewCommand(seedRequest(), ready.siteId, SiteReviewStatus.APPROVED, reason, null)))
            val enablement = succeeded(
                entitlement.enableCurrency(EnableCurrencyCommand(seedRequest(), mid, CurrencyCode("KRW"), EffectivePeriod(OnboardingFixture.t0), evidence)), // D-MOPS-008 ②·C-40(B3-2): seed 경로 행위자(준비 경로만)
            )
            revisionOf(container, ready.merchantId) shouldBe afterRegistration + 3
            val issues = issuesOf(container, ready.merchantId)
            issues.map { it.revision } shouldBe (afterRegistration - 1..afterRegistration + 3).toList()
            issues.all { it.leaseSeq == 0L && it.priority == "HIGH" } shouldBe true

            val snapshot = latestSnapshot(container, ready.merchantId)
            val a = account(snapshot)
            mapOf(
                "revision" to snapshot.get("revision").longValue(),
                "contentHash" to snapshot.get("contentHash").stringValue(),
                "activation" to a.get("activations").single().get("status").stringValue(),
                "confirmation" to a.get("siteConfirmations").single().get("siteId").stringValue(),
                "review" to snapshot.get("sites").first { it.get("siteId").stringValue() == ready.siteId.value }.get("reviewStatus").stringValue(),
                "currency" to a.get("currencies").single().get("currency").stringValue(),
                "window" to a.get("contractWindows").items().map { it.get("siteId").stringValue() }.toSet(),
            ) shouldBe mapOf(
                "revision" to afterRegistration + 3,
                "contentHash" to issues.last().contentHash,
                "activation" to "ACTIVE",
                "confirmation" to ready.siteId.value,
                "review" to "APPROVED",
                "currency" to "KRW",
                "window" to setOf(ready.siteId.value, ready.secondSiteId.value),
            )
            scalar("SELECT effective_to FROM currency_enablement WHERE enablement_id = ?", enablement) shouldBe null
        }

        Then("the stored payload re-hashes to the issued content hash (wire payload minus lease fields is the canonical content)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val snapshot = latestSnapshot(container, entitled.merchantId)
            val content = (snapshot.deepCopy() as tools.jackson.databind.node.ObjectNode).apply {
                remove(listOf("contentHash", "issuedAt", "leaseSeq", "leaseUntil"))
            }
            EntitlementSnapshot.contentHashOf(CanonicalJsonOfNode.write(content)) shouldBe snapshot.get("contentHash").stringValue()
        }

        Then("a rejected command changes nothing (no revision increment, no snapshot) and replays the stored rejection") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val before = revisionOf(container, entitled.merchantId) to issuesOf(container, entitled.merchantId).size
            val command = ApprovePaymentMethodActivationCommand(seedRequest(), entitled.activationId, newEvidence)
            val rejected = AdminCommandResult.Rejected(MasterRejection.ACTIVATION_TRANSITION_NOT_ALLOWED, null, replayed = false)
            entitlement.approvePaymentMethodActivation(command) shouldBe rejected
            entitlement.approvePaymentMethodActivation(command) shouldBe rejected.copy(replayed = true)
            (revisionOf(container, entitled.merchantId) to issuesOf(container, entitled.merchantId).size) shouldBe before
        }

        Then("the same key with the same content replays without a new snapshot; a different content is IdempotencyKeyReused") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val command = SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)
            val id = succeeded(entitlement.suspendPaymentMethodActivation(command))
            val issues = issuesOf(container, entitled.merchantId).size
            entitlement.suspendPaymentMethodActivation(command) shouldBe AdminCommandResult.Succeeded(id, replayed = true)
            entitlement.suspendPaymentMethodActivation(command.copy(reason = com.paycore.master.merchant.domain.DecisionReason("other"))) shouldBe
                AdminCommandResult.IdempotencyKeyReused
            issuesOf(container, entitled.merchantId).size shouldBe issues
        }
    }

    Given("an ACTIVE activation (D-MS-003 transitions through the commands)") {
        Then("suspend → resume needs a new evidence; terminate is final; a new activation row can then be registered (one non-terminated per MID·method)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            succeeded(entitlement.suspendPaymentMethodActivation(SuspendPaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            account(latestSnapshot(container, entitled.merchantId)).get("activations").single().get("status").stringValue() shouldBe "SUSPENDED"
            entitlement.resumePaymentMethodActivation(ResumePaymentMethodActivationCommand(request(), entitled.activationId, evidence)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.ACTIVATION_EVIDENCE_NOT_NEW, null, replayed = false)
            succeeded(entitlement.resumePaymentMethodActivation(ResumePaymentMethodActivationCommand(request(), entitled.activationId, newEvidence)))
            // 재개 시 기존 Site 확인 유지(D-MS-003).
            account(latestSnapshot(container, entitled.merchantId)).get("siteConfirmations").size() shouldBe 1

            succeeded(entitlement.terminatePaymentMethodActivation(TerminatePaymentMethodActivationCommand(request(), entitled.activationId, reason)))
            val afterTerminate = account(latestSnapshot(container, entitled.merchantId))
            (afterTerminate.get("activations").size() to afterTerminate.get("siteConfirmations").size()) shouldBe (0 to 0)

            val renewed = PaymentMethodActivationId(
                succeeded(
                    onboarding.registerPaymentMethodActivation(RegisterPaymentMethodActivationCommand(request(), entitled.mid, PaymentMethod.CARD)),
                ),
            )
            (renewed == entitled.activationId) shouldBe false
            val renewedAccount = account(latestSnapshot(container, entitled.merchantId))
            renewedAccount.get("activations").single().get("status").stringValue() shouldBe "UNDER_REVIEW"
            renewedAccount.get("siteConfirmations").size() shouldBe 0
            scalar(
                "SELECT GROUP_CONCAT(CONCAT(IFNULL(from_status, '-'), '>', to_status) ORDER BY seq) FROM payment_method_activation_event WHERE activation_id = ?",
                entitled.activationId.value,
            ) shouldBe "-" + ">UNDER_REVIEW,UNDER_REVIEW>ACTIVE,ACTIVE>SUSPENDED,SUSPENDED>ACTIVE,ACTIVE>TERMINATED"
        }

        Then("a site confirmation is revoked by an append-only revocation and a site can be confirmed again with a new row") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val primary = SiteActivationConfirmationId(
                scalar("SELECT confirmation_id FROM site_activation_confirmation WHERE activation_id = ?", entitled.activationId.value) as String,
            )
            succeeded(entitlement.revokeSiteActivationConfirmation(RevokeSiteActivationConfirmationCommand(request(), primary, reason, evidence)))
            account(latestSnapshot(container, entitled.merchantId)).get("siteConfirmations").size() shouldBe 0
            entitlement.revokeSiteActivationConfirmation(RevokeSiteActivationConfirmationCommand(request(), primary, reason, evidence)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_CONFIRMATION_ALREADY_REVOKED, null, replayed = false)

            val again = succeeded(
                entitlement.confirmSiteActivation(ConfirmSiteActivationCommand(request(), entitled.mid, entitled.siteId, PaymentMethod.CARD, newEvidence)),
            )
            (again == primary.value) shouldBe false
            account(latestSnapshot(container, entitled.merchantId)).get("siteConfirmations").single().get("siteId").stringValue() shouldBe
                entitled.siteId.value
        }

        Then("confirming a site without an open assignment is refused (ADDITIONAL creation is MASTER-LIFECYCLE-001)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            entitlement.confirmSiteActivation(
                ConfirmSiteActivationCommand(request(), entitled.mid, entitled.ready.secondSiteId, PaymentMethod.CARD, evidence),
            ) shouldBe AdminCommandResult.Rejected(MasterRejection.ASSIGNMENT_NOT_OPEN, null, replayed = false)
        }
    }

    Given("currency enablement and site review commands") {
        Then("a future end is recorded once; retroactive start or end, an overlap and an unknown currency are refused") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val krw = CurrencyEnablementId(scalar("SELECT enablement_id FROM currency_enablement WHERE merchant_account_id = ?", entitled.mid.value) as String)
            val t0 = OnboardingFixture.t0
            mapOf(
                "retro start" to entitlement.enableCurrency(EnableCurrencyCommand(seedRequest(), entitled.mid, CurrencyCode("USD"), EffectivePeriod(t0.minusSeconds(1)), evidence)), // D-MOPS-008 ②·C-40(B3-2): 시험 대상 호출을 seed 경로로 이동(C-40), 기대값 불변
                "overlap" to entitlement.enableCurrency(EnableCurrencyCommand(seedRequest(), entitled.mid, CurrencyCode("KRW"), EffectivePeriod(t0.plusSeconds(5)), evidence)), // D-MOPS-008 ②·C-40(B3-2): 시험 대상 호출을 seed 경로로 이동(C-40), 기대값 불변
                "unknown" to entitlement.enableCurrency(EnableCurrencyCommand(seedRequest(), entitled.mid, CurrencyCode("EUR"), EffectivePeriod(t0), evidence)), // D-MOPS-008 ②·C-40(B3-2): 시험 대상 호출을 seed 경로로 이동(C-40), 기대값 불변
                "retro end" to entitlement.endCurrencyEnablement(EndCurrencyEnablementCommand(request(), krw, t0.minusSeconds(1))),
            ).mapValues { (it.value as AdminCommandResult.Rejected).rejection } shouldBe mapOf(
                "retro start" to MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE,
                "overlap" to MasterRejection.CURRENCY_ENABLEMENT_OVERLAP,
                "unknown" to MasterRejection.CURRENCY_UNKNOWN,
                "retro end" to MasterRejection.CURRENCY_ENABLEMENT_RETROACTIVE,
            )
            succeeded(entitlement.endCurrencyEnablement(EndCurrencyEnablementCommand(request(), krw, t0.plusSeconds(3600))))
            val currency = account(latestSnapshot(container, entitled.merchantId)).get("currencies").single()
            currency.get("to").stringValue() shouldBe "2026-10-09T03:00:00.000Z"
            entitlement.endCurrencyEnablement(EndCurrencyEnablementCommand(request(), krw, t0.plusSeconds(7200))).shouldBeInstanceOf<AdminCommandResult.Rejected>()
                .rejection shouldBe MasterRejection.CURRENCY_ENABLEMENT_ALREADY_ENDED
        }

        Then("a site review decision on a merchant without a MID advances the revision but publishes nothing") {
            val ready = OnboardingFixture.readyMerchant(onboarding)
            val before = revisionOf(container, ready.merchantId)
            succeeded(entitlement.decideSiteReview(DecideSiteReviewCommand(request(), ready.siteId, SiteReviewStatus.REJECTED, reason, evidence)))
            revisionOf(container, ready.merchantId) shouldBe before + 1
            issuesOf(container, ready.merchantId) shouldBe emptyList()
            entitlement.decideSiteReview(DecideSiteReviewCommand(seedRequest(), ready.siteId, SiteReviewStatus.APPROVED, reason, null)) shouldBe
                AdminCommandResult.Rejected(MasterRejection.SITE_REVIEW_TRANSITION_NOT_ALLOWED, null, replayed = false)
        }

        Then("approving a later SITES revision on a merchant with a MID publishes a snapshot whose windows cut the other site at the boundary (n-5)") {
            val entitled = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val t1 = OnboardingFixture.t0.plusSeconds(3600)
            succeeded(
                onboarding.registerContractRevision(
                    RegisterContractRevisionCommand(request(), entitled.ready.contractId, ScopeInput(entitled.merchantId, setOf(entitled.siteId)), EffectivePeriod(t1)),
                ),
            )
            val before = revisionOf(container, entitled.merchantId)
            succeeded(onboarding.approveContractRevision(ApproveContractRevisionCommand(OnboardingFixture.seedRequest(), entitled.ready.contractId, 2))) // D-MOPS-008 ②: seed 경로 행위자
            revisionOf(container, entitled.merchantId) shouldBe before + 1
            account(latestSnapshot(container, entitled.merchantId)).get("contractWindows")
                .associate { it.get("siteId").stringValue() to (it.get("from").stringValue() to it.get("to").let { to -> if (to.isNull) null else to.stringValue() }) } shouldBe
                mapOf(
                    entitled.siteId.value to ("2026-10-09T02:00:00.000Z" to null),
                    entitled.ready.secondSiteId.value to ("2026-10-09T02:00:00.000Z" to "2026-10-09T03:00:00.000Z"),
                )
        }
    }
})

/** Jackson 3 `JsonNode.map`은 노드 자신을 변환하는 멤버라 배열 원소 순회에는 이 helper를 쓴다. */
internal fun JsonNode.items(): List<JsonNode> = (0 until size()).map { get(it) }

/** 시험용: MySQL JSON 컬럼에서 읽은 payload(키 순서가 바뀐다)를 MASTER 정규 JSON 규칙으로 다시 쓴다(조립 golden 대조). */
private object CanonicalJsonOfNode {
    fun write(node: JsonNode): String = com.paycore.master.merchant.domain.CanonicalJson.write(toMap(node))

    @Suppress("UNCHECKED_CAST")
    private fun toMap(node: JsonNode): Map<String, Any?> = convert(node) as Map<String, Any?>

    private fun convert(node: JsonNode): Any? = when {
        node.isNull -> null
        node.isObject -> node.properties().associate { (k, v) -> k to convert(v) }
        node.isArray -> node.items().map { convert(it) }
        node.isBoolean -> node.booleanValue()
        node.isIntegralNumber -> node.longValue()
        node.isString -> node.stringValue()
        else -> error("unexpected JSON node")
    }
}
