package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.adapter.integration.MicrometerMasterOperationalMetrics
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture.csaDefault
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture.institutionRef
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture.midnight1
import com.paycore.master.merchant.application.AuthorizationTermsCommandFixture.midnight2
import com.paycore.master.merchant.application.EntitlementCommandFixture.evidence
import com.paycore.master.merchant.application.EntitlementCommandFixture.issuesOf
import com.paycore.master.merchant.application.EntitlementCommandFixture.latestSnapshot
import com.paycore.master.merchant.application.EntitlementCommandFixture.reason
import com.paycore.master.merchant.application.EntitlementCommandFixture.revisionOf
import com.paycore.master.merchant.application.OnboardingFixture.request
import com.paycore.master.merchant.application.OnboardingFixture.runtime
import com.paycore.master.merchant.application.OnboardingFixture.succeeded
import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.ApproveCardRouteActivationCommand
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.domain.CardRouteActivationId
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.EndPolicyAssignmentCommand
import com.paycore.master.merchant.application.provided.EndTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.EntitlementLeaseUseCase
import com.paycore.master.merchant.application.provided.LimitTargetInput
import com.paycore.master.merchant.application.provided.MerchantAuthorizationTermsUseCase
import com.paycore.master.merchant.application.provided.MerchantEntitlementUseCase
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterPolicyVersionCommand
import com.paycore.master.merchant.application.provided.RegisterTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.ReplaceTransactionLimitPolicyCommand
import com.paycore.master.merchant.application.provided.SuspendCardRouteActivationCommand
import com.paycore.master.merchant.domain.CurrencyCode
import com.paycore.master.merchant.domain.ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.LimitValue
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.PolicyCode
import com.paycore.master.merchant.domain.PolicyContentStatus
import com.paycore.master.merchant.domain.PolicyKind
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.testcontainers.mysql.MySQLContainer

/**
 * 승인 시작 조건 명령(MASTER-SNAPSHOT-002)을 실제 MySQL 8.4.11(runtime 계정)에서 확인한다: Merchant 범위 명령은 같은 MASTER TX에서 업무 행·이력·
 * revision +1·Snapshot 발행(schemaVersion 2, HIGH)을 함께 커밋하고, 거절은 아무것도 바꾸지 않는다. 정책 버전 등록(전역 등록부)은 revision을 올리지
 * 않는다. 옛 schemaVersion 발행 뒤의 lease 갱신은 revision을 올린 승격 발행이다(§5.2). 값은 Demo.
 */
@MasterDbTest
class MerchantAuthorizationTermsServiceSpec(
    private val onboarding: MerchantOnboardingUseCase,
    private val entitlement: MerchantEntitlementUseCase,
    private val terms: MerchantAuthorizationTermsUseCase,
    private val leases: EntitlementLeaseUseCase,
    private val faults: MasterFaultInjector,
    private val container: MySQLContainer,
    private val clock: MutableMasterClock,
    private val registry: MeterRegistry,
) : BehaviorSpec({

    afterEach {
        faults.reset()
        clock.reset()
    }

    class Injected(point: String) : RuntimeException("injected failure after $point")

    fun count(sql: String, vararg args: Any?): Long = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
    }

    fun text(sql: String, vararg args: Any?): String? = runtime(container).use { c ->
        c.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
    }

    fun rejected(result: AdminCommandResult): MasterRejection = result.shouldBeInstanceOf<AdminCommandResult.Rejected>().rejection

    Given("an entitled merchant") {
        Then("each merchant-scoped terms command advances the revision by one and publishes one HIGH current-schemaVersion snapshot; the policy version registry does not") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val r0 = revisionOf(container, e.merchantId)
            val fee = AuthorizationTermsCommandFixture.registerVersion(terms, PolicyKind.FEE_SCHEDULE)
            val stl = AuthorizationTermsCommandFixture.registerVersion(terms, PolicyKind.SETTLEMENT_POLICY)
            revisionOf(container, e.merchantId) shouldBe r0
            AuthorizationTermsCommandFixture.assign(terms, e, fee)
            AuthorizationTermsCommandFixture.assign(terms, e, stl)
            val limit = AuthorizationTermsCommandFixture.registerCsaDefault(terms, e)
            AuthorizationTermsCommandFixture.activeRoute(terms, e)
            revisionOf(container, e.merchantId) shouldBe r0 + 5
            issuesOf(container, e.merchantId).takeLast(5).map { it.revision to it.priority } shouldBe (1L..5L).map { r0 + it to "HIGH" }
            // AUTHN-001: 현재 schemaVersion은 3(v2 + sites[].channel). 기대값은 "현재 버전으로 발행"이라는 같은 의미다.
            count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision > ? AND schema_version = $ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION", e.merchantId.value, r0) shouldBe 5L
            val account = latestSnapshot(container, e.merchantId).get("accounts").single()
            listOf(
                account.get("limitPolicies").single().get("policyId").stringValue(),
                account.get("limitPolicies").single().get("from").stringValue(),
                account.get("policyAssignments").toList().map { it.get("kind").stringValue() }.toSet(),
                account.get("cardRoutes").single().get("status").stringValue(),
                account.get("cardRoutes").single().get("institutionMerchantRef").stringValue(),
            ) shouldBe listOf(limit.value, "2026-10-09T15:00:00.000Z", setOf("FEE_SCHEDULE", "SETTLEMENT_POLICY"), "ACTIVE", institutionRef.value)
        }

        Then("rejections change nothing (revision, issues): off-midnight, retroactive, overlapping, mismatched CSA, route without method, duplicate route") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            AuthorizationTermsCommandFixture.registerCsaDefault(terms, e)
            AuthorizationTermsCommandFixture.activeRoute(terms, e)
            val (rev, issues) = revisionOf(container, e.merchantId) to issuesOf(container, e.merchantId).size
            val otherReady = OnboardingFixture.readyMerchant(onboarding)
            mapOf(
                "off midnight" to rejected(terms.registerTransactionLimitPolicy(csaDefault(e, EffectivePeriod(midnight1.plusSeconds(3600))))),
                "overlap" to rejected(terms.registerTransactionLimitPolicy(csaDefault(e, EffectivePeriod(midnight2)))),
                "retroactive assignment" to rejected(
                    terms.assignPolicy(
                        com.paycore.master.merchant.application.provided.AssignPolicyCommand(
                            request(), e.mid, e.ready.agreementId, CurrencyCode("KRW"), AuthorizationTermsCommandFixture.registerVersion(terms, PolicyKind.FEE_SCHEDULE),
                            EffectivePeriod(OnboardingFixture.t0.minusSeconds(1)), reason,
                        ),
                    ),
                ),
                "assignment CSA mismatch" to rejected(
                    terms.assignPolicy(
                        com.paycore.master.merchant.application.provided.AssignPolicyCommand(
                            request(), e.mid, otherReady.agreementId, CurrencyCode("KRW"), AuthorizationTermsCommandFixture.registerVersion(terms, PolicyKind.FEE_SCHEDULE),
                            EffectivePeriod(OnboardingFixture.t0), reason,
                        ),
                    ),
                ),
                "CSA of another merchant" to rejected(
                    terms.registerTransactionLimitPolicy(
                        RegisterTransactionLimitPolicyCommand(
                            request(), LimitTargetInput.CsaDefault(e.ready.contractId, otherReady.agreementId), CurrencyCode("KRW"),
                            LimitValue.Unlimited, LimitValue.Unlimited, EffectivePeriod(midnight1), reason,
                        ),
                    ),
                ),
                "duplicate route" to rejected(terms.registerCardRouteActivation(AuthorizationTermsCommandFixture.route(e))),
            ) shouldBe mapOf(
                "off midnight" to MasterRejection.LIMIT_POLICY_BOUNDARY_NOT_SEOUL_MIDNIGHT,
                "overlap" to MasterRejection.LIMIT_POLICY_OVERLAP,
                "retroactive assignment" to MasterRejection.POLICY_ASSIGNMENT_RETROACTIVE,
                "assignment CSA mismatch" to MasterRejection.POLICY_ASSIGNMENT_AGREEMENT_MISMATCH,
                "CSA of another merchant" to MasterRejection.AGREEMENT_NOT_FOUND,
                "duplicate route" to MasterRejection.CARD_ROUTE_DUPLICATE,
            )
            (revisionOf(container, e.merchantId) to issuesOf(container, e.merchantId).size) shouldBe (rev to issues)
            val (ready, ids) = EntitlementCommandFixture.issuedWithActivation(onboarding)
            val withoutMethod = EntitlementCommandFixture.Entitled(ready, ids.first, ids.second).also {
                succeeded(entitlement.terminatePaymentMethodActivation(
                    com.paycore.master.merchant.application.provided.TerminatePaymentMethodActivationCommand(request(), ids.second, reason),
                ))
            }
            rejected(terms.registerCardRouteActivation(AuthorizationTermsCommandFixture.route(withoutMethod))) shouldBe MasterRejection.CARD_ROUTE_METHOD_NOT_ACTIVATED
        }

        Then("end and replace keep history: the old policy is closed at the switch midnight and the replacement starts there (no gap); a suspended route is carried as SUSPENDED") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val ready = AuthorizationTermsCommandFixture.ready(terms, e)
            val replacement = succeeded(
                terms.replaceTransactionLimitPolicy(ReplaceTransactionLimitPolicyCommand(request(), ready.limit, LimitValue.Limited(2_000_000), LimitValue.Unlimited, midnight2, reason)),
            )
            text("SELECT DATE_FORMAT(effective_to, '%Y-%m-%dT%H:%i:%s.%fZ') FROM transaction_limit_policy WHERE policy_id = ?", ready.limit.value) shouldBe
                "2026-10-10T15:00:00.000000Z"
            rejected(terms.endTransactionLimitPolicy(EndTransactionLimitPolicyCommand(request(), ready.limit, midnight2))) shouldBe MasterRejection.LIMIT_POLICY_ALREADY_ENDED
            succeeded(terms.endPolicyAssignment(EndPolicyAssignmentCommand(request(), ready.fee, OnboardingFixture.t0.plusSeconds(3600))))
            succeeded(terms.suspendCardRouteActivation(SuspendCardRouteActivationCommand(request(), ready.route, reason)))
            val account = latestSnapshot(container, e.merchantId).get("accounts").single()
            account.get("limitPolicies").toList().map { it.get("policyId").stringValue() }.toSet() shouldBe setOf(ready.limit.value, replacement)
            account.get("cardRoutes").single().get("status").stringValue() shouldBe "SUSPENDED"
            account.get("policyAssignments").toList().single { it.get("kind").stringValue() == "FEE_SCHEDULE" }.get("to").stringValue() shouldBe "2026-10-09T03:00:00.000Z"
        }
    }

    Given("the institution merchant reference (internal identifier grade, D-MS2-005)") {
        Then("it is in the signed snapshot and the audit history, but not in the integration event payload nor in any log line") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            CapturedLogs().use { logs ->
                AuthorizationTermsCommandFixture.activeRoute(terms, e)
                logs.lines().none { it.contains(institutionRef.value) } shouldBe true
            }
            count(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type LIKE 'CardRouteActivation%' AND aggregate_id IN " +
                    "(SELECT activation_id FROM card_route_activation WHERE merchant_account_id = ?) AND JSON_EXTRACT(payload, '$.institutionMerchantRef') IS NOT NULL",
                e.mid.value,
            ) shouldBe 0L
            count(
                "SELECT COUNT(*) FROM master_change_history WHERE change_type = 'CardRouteActivationRegistered' AND " +
                    "JSON_UNQUOTE(JSON_EXTRACT(detail, '$.merchantAccountId')) = ? AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.institutionMerchantRef')) = ?",
                e.mid.value, institutionRef.value,
            ) shouldBe 1L
        }
    }

    Given("a limit policy registration with a failure injected after each storage point") {
        listOf(MasterFaultPoint.MERCHANT_UPDATE, MasterFaultPoint.CHANGE_LOG_APPEND, MasterFaultPoint.OUTBOX_APPEND, MasterFaultPoint.ISSUE_RECORD, MasterFaultPoint.ADMIN_COMPLETE)
            .forEach { point ->
                Then("a failure after $point leaves no policy, revision, issue or request; the same key then succeeds") {
                    val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
                    val command = csaDefault(e)
                    fun state() = listOf(
                        count("SELECT COUNT(*) FROM transaction_limit_policy WHERE merchant_id = ?", e.merchantId.value), revisionOf(container, e.merchantId),
                        issuesOf(container, e.merchantId).size.toLong(), count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value),
                    )
                    val before = state()
                    faults.after(point) { throw Injected(point.name) }
                    shouldThrow<Injected> { terms.registerTransactionLimitPolicy(command) }
                    state() shouldBe before
                    faults.reset()
                    succeeded(terms.registerTransactionLimitPolicy(command))
                    state() shouldBe listOf(before[0] + 1, before[1] + 1, before[2] + 1, 1L)
                }
            }
    }

    Given("concurrent commands (real MySQL, separate connections)") {
        Then("two different keys registering the same CSA default at once: exactly one succeeds, the other is LIMIT_POLICY_OVERLAP; one revision step") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val r0 = revisionOf(container, e.merchantId)
            val results = OnboardingFixture.concurrently(4) { terms.registerTransactionLimitPolicy(csaDefault(e)) }.map { it.getOrThrow() }
            results.count { it is AdminCommandResult.Succeeded } shouldBe 1
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection }.toSet() shouldBe setOf(MasterRejection.LIMIT_POLICY_OVERLAP)
            count("SELECT COUNT(*) FROM transaction_limit_policy WHERE merchant_id = ?", e.merchantId.value) shouldBe 1L
            revisionOf(container, e.merchantId) shouldBe r0 + 1
        }

        // 전역 등록부는 Merchant 행 같은 직렬화 지점이 없다: 같은 코드 동시 등록은 UNIQUE 충돌 → 새 TX 재판정(기술 재시도 상한 3회)으로 다음 번호를
        // 받는다. 동시 2건은 각자 최대 한 번 충돌하므로 둘 다 성공한다. 경합이 상한을 넘으면 TemporarilyUnavailable(같은 키 재요청으로 안전히 재시도).
        Then("concurrent registrations of the same policy code get distinct consecutive version numbers (UNIQUE + retry)") {
            val code = AuthorizationTermsCommandFixture.code("FEE")
            val results = OnboardingFixture.concurrently(2) {
                terms.registerPolicyVersion(RegisterPolicyVersionCommand(request(), PolicyKind.FEE_SCHEDULE, code, PolicyContentStatus.DEMO, null, reason))
            }.map { it.getOrThrow() }
            results.all { it is AdminCommandResult.Succeeded } shouldBe true
            runtime(container).use { c ->
                c.prepareStatement("SELECT version_no FROM policy_version WHERE policy_code = ? ORDER BY version_no").use { ps ->
                    ps.setString(1, code.value)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getInt(1)) } }
                }
            } shouldBe listOf(1, 2)
        }
    }

    // AUTHN-001 §9.2: v1 발행 뒤(MS2 승격 경로)와 v2 발행 뒤(SchemaPromotionV3 — sites[].channel 추가) 모두 같은 승격 경로로 현재 버전(3)이 된다.
    listOf(1, 2).forEach { oldVersion ->
    Given("a merchant whose latest issue is a schemaVersion $oldVersion snapshot (issued before this upgrade)") {
        Then("the lease renewal promotes it: revision +1, HIGH, the current schemaVersion — never the same revision with new content (PAYMENT would see INTEGRITY_CONFLICT)") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val latest = issuesOf(container, e.merchantId).last()
            // 업그레이드 전 발행을 흉내 낸다(시험 fixture — 운영 계정은 이 표를 UPDATE할 수 없다, root로만).
            MasterDbAccounts.root(container).use { c ->
                c.prepareStatement("UPDATE paycore_master.entitlement_snapshot_issue SET schema_version = ? WHERE merchant_id = ? AND revision = ? AND lease_seq = ?").use { ps ->
                    ps.setInt(1, oldVersion)
                    ps.setString(2, e.merchantId.value)
                    ps.setLong(3, latest.revision)
                    ps.setLong(4, latest.leaseSeq)
                    ps.executeUpdate() shouldBe 1
                }
            }
            val promotions = registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            leases.renewDueLeases()
            val promoted = issuesOf(container, e.merchantId).last()
            listOf(promoted.revision, promoted.leaseSeq, promoted.priority) shouldBe listOf(latest.revision + 1, 0L, "HIGH")
            revisionOf(container, e.merchantId) shouldBe latest.revision + 1
            count("SELECT schema_version FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision = ?", e.merchantId.value, promoted.revision) shouldBe ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION.toLong()
            (registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0) shouldBe promotions + 1
            clock.set(OnboardingFixture.t0.plusMillis(3000))
            leases.renewDueLeases()
            issuesOf(container, e.merchantId).last().let { listOf(it.revision, it.leaseSeq, it.priority) } shouldBe listOf(promoted.revision, 1L, "LOW")
            // v3 내용(AUTHN-001 §9.1): 승격 발행의 Site마다 channel이 실린다.
            latestSnapshot(container, e.merchantId).get("sites").all { it.has("channel") } shouldBe true
        }
    }
    }

    // ── review round1 m-1·m-2·m-3: rollback·경쟁 시험 보강(실제 MySQL, 별도 연결) ─────────────────────────────────────────

    /** 업그레이드 전 발행을 흉내 낸다(시험 fixture — 운영 runtime 계정은 이 표를 UPDATE할 수 없다, root로만). */
    fun markLatestIssueAsV1(e: EntitlementCommandFixture.Entitled): EntitlementCommandFixture.IssueRow {
        val latest = issuesOf(container, e.merchantId).last()
        MasterDbAccounts.root(container).use { c ->
            c.prepareStatement("UPDATE paycore_master.entitlement_snapshot_issue SET schema_version = 1 WHERE merchant_id = ? AND revision = ? AND lease_seq = ?").use { ps ->
                ps.setString(1, e.merchantId.value)
                ps.setLong(2, latest.revision)
                ps.setLong(3, latest.leaseSeq)
                ps.executeUpdate() shouldBe 1
            }
        }
        return latest
    }

    fun currentSchemaIssues(e: EntitlementCommandFixture.Entitled, revision: Long): Long =
        count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision = ? AND schema_version = $ENTITLEMENT_SNAPSHOT_SCHEMA_VERSION", e.merchantId.value, revision)

    Given("a schema promotion (renewal after a v1 issue) with a failure injected inside its transaction") {
        listOf(MasterFaultPoint.MERCHANT_UPDATE, MasterFaultPoint.ISSUE_RECORD).forEach { point ->
            Then("a failure after $point leaves neither the revision increment nor any issue/outbox row and is not counted as a promotion; the next renewal promotes") {
                val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
                val latest = markLatestIssueAsV1(e)
                val promotions = registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0
                val outboxBefore = count("SELECT COUNT(*) FROM outbox_event WHERE event_category = 'ENTITLEMENT_SNAPSHOT' AND aggregate_id = ?", e.merchantId.value)
                clock.set(OnboardingFixture.t0.plusMillis(1500))
                faults.after(point) { throw Injected(point.name) }
                leases.renewDueLeases()
                listOf(
                    revisionOf(container, e.merchantId), issuesOf(container, e.merchantId).last(),
                    count("SELECT COUNT(*) FROM outbox_event WHERE event_category = 'ENTITLEMENT_SNAPSHOT' AND aggregate_id = ?", e.merchantId.value),
                    registry.find(MicrometerMasterOperationalMetrics.SCHEMA_PROMOTIONS).counter()?.count() ?: 0.0,
                ) shouldBe listOf(latest.revision, latest, outboxBefore, promotions)
                faults.reset()
                leases.renewDueLeases()
                revisionOf(container, e.merchantId) shouldBe latest.revision + 1
                currentSchemaIssues(e, latest.revision + 1) shouldBe 1L
            }
        }
    }

    Given("two lease renewal runs at once while the latest issue is v1") {
        Then("the merchant lock serializes them: the revision advances once and exactly one schemaVersion 2 issue is written for it") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val latest = markLatestIssueAsV1(e)
            clock.set(OnboardingFixture.t0.plusMillis(1500))
            OnboardingFixture.concurrently(2) { leases.renewDueLeases() }.forEach { it.getOrThrow() }
            revisionOf(container, e.merchantId) shouldBe latest.revision + 1
            currentSchemaIssues(e, latest.revision + 1) shouldBe 1L
            count("SELECT COUNT(*) FROM entitlement_snapshot_issue WHERE merchant_id = ? AND revision > ?", e.merchantId.value, latest.revision) shouldBe 1L
        }
    }

    Given("a card route registration and a limit policy replacement with a failure injected after the snapshot issue record") {
        Then("the registration leaves no route row, no route history and no revision step; the same key then succeeds") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val command = AuthorizationTermsCommandFixture.route(e)
            fun state() = listOf(
                count("SELECT COUNT(*) FROM card_route_activation WHERE merchant_account_id = ?", e.mid.value),
                count("SELECT COUNT(*) FROM card_route_activation_event v JOIN card_route_activation r ON r.activation_id = v.activation_id WHERE r.merchant_account_id = ?", e.mid.value),
                revisionOf(container, e.merchantId), issuesOf(container, e.merchantId).size.toLong(),
            )
            val before = state()
            faults.after(MasterFaultPoint.ISSUE_RECORD) { throw Injected("ISSUE_RECORD") }
            shouldThrow<Injected> { terms.registerCardRouteActivation(command) }
            state() shouldBe before
            faults.reset()
            succeeded(terms.registerCardRouteActivation(command))
            state() shouldBe listOf(before[0] + 1, before[1] + 1, before[2] + 1, before[3] + 1)
        }

        Then("the replacement leaves the old policy open and no new policy; the same key then switches without a gap") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val old = AuthorizationTermsCommandFixture.registerCsaDefault(terms, e)
            val command = ReplaceTransactionLimitPolicyCommand(request(), old, LimitValue.Limited(2_000_000), LimitValue.Unlimited, midnight2, reason)
            fun state() = listOf<Any?>(
                count("SELECT COUNT(*) FROM transaction_limit_policy WHERE merchant_id = ?", e.merchantId.value),
                text("SELECT DATE_FORMAT(effective_to, '%Y-%m-%d %H:%i:%s.%f') FROM transaction_limit_policy WHERE policy_id = ?", old.value),
                revisionOf(container, e.merchantId),
            )
            val before = state()
            faults.after(MasterFaultPoint.ISSUE_RECORD) { throw Injected("ISSUE_RECORD") }
            shouldThrow<Injected> { terms.replaceTransactionLimitPolicy(command) }
            state() shouldBe before
            before[1] shouldBe null
            faults.reset()
            succeeded(terms.replaceTransactionLimitPolicy(command))
            state() shouldBe listOf(2L, "2026-10-10 15:00:00.000000", (before[2] as Long) + 1)
        }
    }

    Given("concurrent lifecycle commands on the same terms (different keys)") {
        Then("two replacements of the same policy at once: exactly one succeeds, the other is LIMIT_POLICY_ALREADY_ENDED; one new policy, one revision step") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val old = AuthorizationTermsCommandFixture.registerCsaDefault(terms, e)
            val r0 = revisionOf(container, e.merchantId)
            val results = OnboardingFixture.concurrently(2) { i ->
                terms.replaceTransactionLimitPolicy(ReplaceTransactionLimitPolicyCommand(request(), old, LimitValue.Limited(2_000_000L + i), LimitValue.Unlimited, midnight2, reason))
            }.map { it.getOrThrow() }
            results.count { it is AdminCommandResult.Succeeded } shouldBe 1
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection } shouldBe listOf(MasterRejection.LIMIT_POLICY_ALREADY_ENDED)
            count("SELECT COUNT(*) FROM transaction_limit_policy WHERE merchant_id = ?", e.merchantId.value) shouldBe 2L
            count("SELECT COUNT(*) FROM transaction_limit_policy WHERE merchant_id = ? AND effective_to IS NULL", e.merchantId.value) shouldBe 1L
            revisionOf(container, e.merchantId) shouldBe r0 + 1
        }

        Then("approvals and suspensions of one card route at once serialize on the merchant row: the history is a valid chain and each success advanced the revision once") {
            val e = EntitlementCommandFixture.entitled(onboarding, entitlement)
            val route = CardRouteActivationId(succeeded(terms.registerCardRouteActivation(AuthorizationTermsCommandFixture.route(e))))
            val r0 = revisionOf(container, e.merchantId)
            val results = OnboardingFixture.concurrently(6) { i ->
                if (i % 2 == 0) {
                    terms.approveCardRouteActivation(ApproveCardRouteActivationCommand(request(), route, evidence))
                } else {
                    terms.suspendCardRouteActivation(SuspendCardRouteActivationCommand(request(), route, reason))
                }
            }.map { it.getOrThrow() }
            val successes = results.count { it is AdminCommandResult.Succeeded }
            results.filterIsInstance<AdminCommandResult.Rejected>().map { it.rejection }.toSet() shouldBe setOf(MasterRejection.CARD_ROUTE_TRANSITION_NOT_ALLOWED)
            val chain = runtime(container).use { c ->
                c.prepareStatement("SELECT from_status, to_status FROM card_route_activation_event WHERE activation_id = ? ORDER BY seq").use { ps ->
                    ps.setString(1, route.value)
                    ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString(1) to rs.getString(2)) } }
                }
            }
            chain.zipWithNext().all { (a, b) -> a.second == b.first } shouldBe true
            chain.size shouldBe successes + 1
            chain.drop(1).first() shouldBe ("UNDER_REVIEW" to "ACTIVE")
            text("SELECT status FROM card_route_activation WHERE activation_id = ?", route.value) shouldBe chain.last().second
            revisionOf(container, e.merchantId) shouldBe r0 + successes
            (successes in 1..2) shouldBe true
        }
    }

    Given("a policy version registration whose unique conflict persists on every attempt") {
        Then("the retries are exhausted (TemporarilyUnavailable, counted), no partial row or request record remains, and the same key then succeeds") {
            val code = AuthorizationTermsCommandFixture.code("FEE")
            val command = RegisterPolicyVersionCommand(request(), PolicyKind.FEE_SCHEDULE, code, PolicyContentStatus.DEMO, null, reason)
            fun rows() = listOf(
                count("SELECT COUNT(*) FROM policy_version WHERE policy_code = ?", code.value),
                count("SELECT COUNT(*) FROM admin_request WHERE idempotency_key = ?", command.request.idempotencyKey.value),
            )
            val exhausted = registry.find(MicrometerMasterOperationalMetrics.ADMIN_FAILURES).tags("kind", "retries_exhausted", "operation", "RegisterPolicyVersion")
                .counter()?.count() ?: 0.0
            // 같은 TX에서 버전 행을 쓴 직후(이력 기록 지점) 매 시도마다 (종류, 코드, 번호) UNIQUE 충돌을 낸다 — 동시 등록 경합이 상한을 넘는 상황.
            faults.after(MasterFaultPoint.CHANGE_LOG_APPEND) { throw PersistenceFailure.UniqueConflict(StoreConstraint.POLICY_VERSION_NO) }
            shouldThrow<AdminCommandFailure.TemporarilyUnavailable> { terms.registerPolicyVersion(command) }
            rows() shouldBe listOf(0L, 0L)
            (registry.find(MicrometerMasterOperationalMetrics.ADMIN_FAILURES).tags("kind", "retries_exhausted", "operation", "RegisterPolicyVersion")
                .counter()?.count() ?: 0.0) shouldBe exhausted + 1
            faults.reset()
            succeeded(terms.registerPolicyVersion(command))
            rows() shouldBe listOf(1L, 1L)
        }
    }
})
