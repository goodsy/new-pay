package com.paycore.payment.entitlement

import com.paycore.payment.entitlement.EntitlementEnvelopes.T0
import com.paycore.payment.entitlement.EntitlementEnvelopes.body
import com.paycore.payment.entitlement.EntitlementEnvelopes.envelope
import com.paycore.payment.entitlement.EntitlementEnvelopes.send
import com.paycore.payment.entitlement.EntitlementEnvelopes.signature
import com.paycore.payment.entitlement.adapter.integration.MicrometerEntitlementMetrics
import com.paycore.payment.entitlement.domain.SnapshotFixture
import com.paycore.payment.payment.application.required.AdmissionResult
import com.paycore.payment.payment.application.required.AuthorizationAdmissionPort
import com.paycore.payment.payment.application.required.MerchantCodePort
import com.paycore.payment.payment.application.required.SiteWebHostPort
import com.paycore.payment.payment.application.required.SiteWebHostResult
import com.paycore.payment.payment.application.required.SiteWebHostUnavailable
import com.paycore.payment.payment.domain.CurrencyCode
import com.paycore.payment.payment.domain.MerchantAccountId
import com.paycore.payment.payment.domain.MerchantSiteId
import com.paycore.payment.payment.domain.PaymentMethod
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.WebApplicationContext
import java.util.UUID

/**
 * schemaVersion 4 배포 순서(MERCHANT-CODE-001 §6·§8 MC-4-5, MASTER-SNAPSHOT-002 §5.4 — V3 선례 [SchemaRolloutOrderV3Spec]): PAYMENT(v1~v4 해석 + V36) 먼저, MASTER(v4 조립·승격) 나중.
 * - 역순(PAYMENT가 해석하지 못하는 더 새 버전이 먼저 도착): head가 VERSION_UNSUPPORTED로 막혀 결제창 host·승인 판정 모두 fail-closed(가용성 사고, 금융 안전).
 *   PAYMENT가 이미 4를 지원하므로 "해석 못 하는 새 버전"은 지원 집합 밖 첫 값(5)으로 대표한다. 코드 없는 head는 외부 필드를 생략한다(null).
 * - 정순: v3 head에 MASTER 승격 발행(v4, revision + 1)이 APPLIED되면 코드가 전파되고 v3로 하던 host·승인 판정은 그대로 동작한다.
 * - 대조: v3 head와 **같은 revision**의 v4 내용(같은 revision 재발행)은 INTEGRITY_CONFLICT로 head를 막는다 — 승격은 revision +1이어야 한다.
 * - MC-4-4 경보: revision이 오르며 코드가 바뀐 v4(MID 있는 가맹점에서는 불가능한 상태)는 정상 적용 + `merchant_code_changed_with_mid` +1.
 */
@EntitlementDbTest
class SchemaRolloutOrderV4Spec(
    private val context: WebApplicationContext,
    private val hosts: SiteWebHostPort,
    private val admission: AuthorizationAdmissionPort,
    private val codes: MerchantCodePort,
    private val registry: MeterRegistry,
    private val transactionManager: PlatformTransactionManager,
    private val dataSource: javax.sql.DataSource,
) : BehaviorSpec({

    val mvc = MockMvcBuilders.webAppContextSetup(context).build()
    val tx = TransactionTemplate(transactionManager)
    val at = T0.plusSeconds(5)

    fun host(ids: SnapshotFixture.Ids): SiteWebHostResult = checkNotNull(tx.execute { hosts.resolve(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), at) })

    fun admit(ids: SnapshotFixture.Ids): AdmissionResult = checkNotNull(
        tx.execute { admission.evaluate(MerchantAccountId(ids.mid), MerchantSiteId(ids.siteId), CurrencyCode("KRW"), PaymentMethod.CARD, SnapshotFixture.ACQUIRER, at) },
    )

    fun code(ids: SnapshotFixture.Ids): String? = codes.merchantCodeOf(MerchantAccountId(ids.mid))

    fun outcome(r: EntitlementEnvelopes.Response): String = r.json!!.get("outcome").stringValue()

    fun alerts(): Double = registry.find(MicrometerEntitlementMetrics.MERCHANT_CODE_CHANGED_WITH_MID).counter()?.count() ?: 0.0

    Given("reverse order: a schema version PAYMENT cannot read arrives on a v3 head") {
        Then("the head is blocked (UNSUPPORTED), the window host and the authorization admission fail closed, and no merchant code is exposed") {
            val ids = SnapshotFixture.ids()
            send(mvc, body(SnapshotFixture.entitledWithChannels(ids, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            code(ids) shouldBe null
            val newer = envelope(UUID.randomUUID().toString(), SnapshotFixture.UNSUPPORTED_SCHEMA_VERSION, ids.merchantId, 2, """{"futureField":true}""")
            outcome(send(mvc, newer, signature(newer))) shouldBe "UNSUPPORTED"
            host(ids) shouldBe SiteWebHostResult.Unavailable(SiteWebHostUnavailable.VERSION_UNSUPPORTED)
            admit(ids).shouldBeInstanceOf<AdmissionResult.Unavailable>()
            code(ids) shouldBe null
        }
        // 리뷰 NIT-1(Recoverability): 막힘 → PAYMENT 업그레이드 → 같은 revision 재처리 → APPLIED 회복(SnapshotApplicationRule reprocessesBlockedVersion — 규칙 단위 선례
        // SnapshotApplicationRuleSpec "reprocessing revision 5 as a supported snapshot (after upgrade)"). 한 빌드 안에서 지원 집합을 바꿀 수 없으므로 "업그레이드 전에 도착한 v4"는
        // 지원 밖 버전 envelope로 대표하고, 업그레이드 뒤 MASTER가 같은 revision을 다시 보내는 것(lease 갱신 — 새 eventId)은 PAYMENT가 읽는 v4 envelope로 보낸다.
        Then("after the PAYMENT upgrade the same revision is reprocessed as v4 (lease renewal, new event id): APPLIED, the block clears, host/admission recover and the code appears") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            send(mvc, body(SnapshotFixture.entitledWithChannels(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            val early = envelope(UUID.randomUUID().toString(), SnapshotFixture.UNSUPPORTED_SCHEMA_VERSION, ids.merchantId, 2, """{"futureField":true}""")
            outcome(send(mvc, early, signature(early))) shouldBe "UNSUPPORTED"
            admit(ids).shouldBeInstanceOf<AdmissionResult.Unavailable>()
            val alertsBefore = alerts()
            outcome(send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 2), SnapshotFixture.leaseAt(T0, seq = 1)))) shouldBe "APPLIED"
            host(ids).shouldBeInstanceOf<SiteWebHostResult.WebHost>().host shouldBe SnapshotFixture.WEB_HOST
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
            code(ids) shouldBe SnapshotFixture.MERCHANT_CODE
            alerts() shouldBe alertsBefore
        }
    }

    Given("correct order: PAYMENT reads v4 before MASTER promotes") {
        Then("the v3 head has no code (field omitted), the promotion issue (v4, revision + 1) is APPLIED, the code is exposed and host/admission keep working") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            send(mvc, body(SnapshotFixture.entitledWithChannels(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            code(ids) shouldBe null
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
            val alertsBefore = alerts()
            outcome(send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 2), SnapshotFixture.leaseAt(T0)))) shouldBe "APPLIED"
            code(ids) shouldBe SnapshotFixture.MERCHANT_CODE
            host(ids).shouldBeInstanceOf<SiteWebHostResult.WebHost>().host shouldBe SnapshotFixture.WEB_HOST
            admit(ids).shouldBeInstanceOf<AdmissionResult.Admitted>()
            // 코드가 처음 생긴 승격은 "변경"이 아니다(경보 없음).
            alerts() shouldBe alertsBefore
        }
    }

    Given("control: the same revision re-issued with v4 content (not a promotion)") {
        Then("PAYMENT blocks the head with INTEGRITY_CONFLICT (same revision, different content hash) and admission fails closed") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            send(mvc, body(SnapshotFixture.entitledWithChannels(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            outcome(send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0)))) shouldBe "INTEGRITY_CONFLICT"
            admit(ids).shouldBeInstanceOf<AdmissionResult.Unavailable>()
        }
    }

    // 리뷰 NIT-2(MC-3a 선례 AdminSchemaSpec V15): V36 CHECK를 runtime 계정으로 증명하고, 문장별 ALGORITHM 원문을 고정한다(누락·변경 = RED — MySQL이 더 무거운 방식으로 조용히 바꾸지 못하게).
    Given("V36 database proof (runtime account) and SQL text") {
        Then("the runtime account cannot store a malformed code or a code on an empty head (3819 ck_entitlement_head_merchant_code); a well-formed code on a filled head is stored") {
            fun insert(revision: Long, code: String): Int? = dataSource.connection.use { c ->
                val filled = revision >= 1
                try {
                    c.prepareStatement(
                        "INSERT INTO entitlement_head (merchant_id, revision, content_hash, schema_version, issued_at, lease_seq, lease_until, applied_at, version, merchant_code) " +
                            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?)",
                    ).use { ps ->
                        val at = java.sql.Timestamp.valueOf("2026-10-14 00:00:00")
                        ps.setString(1, "mer_V36" + UUID.randomUUID().toString().replace("-", "").substring(0, 22).uppercase())
                        ps.setLong(2, revision)
                        ps.setString(3, if (filled) "ec1:" + "0".repeat(64) else null)
                        ps.setObject(4, if (filled) 4 else null)
                        ps.setTimestamp(5, if (filled) at else null)
                        ps.setObject(6, if (filled) 0L else null)
                        ps.setTimestamp(7, if (filled) java.sql.Timestamp.valueOf("2026-10-14 01:00:00") else null)
                        ps.setTimestamp(8, if (filled) at else null)
                        ps.setString(9, code)
                        ps.executeUpdate()
                    }
                    null
                } catch (e: java.sql.SQLException) {
                    withClue(code) { e.message!!.contains("ck_entitlement_head_merchant_code") shouldBe true }
                    e.errorCode
                }
            }
            dataSource.connection.use { c -> c.createStatement().use { st -> st.executeQuery("SELECT CURRENT_USER()").use { rs -> rs.next(); rs.getString(1) } } } shouldBe
                "${com.paycore.payment.PaymentDbAccounts.PAYMENT_RUNTIME_USER}@%"
            mapOf(
                "revision 0 with a code" to insert(0, "emptyshop01"),
                "upper case" to insert(1, "DEMOSHOP01"),
                "too short" to insert(1, "shop1"),
                "leading digit" to insert(1, "1shopabc"),
                "underscore" to insert(1, "shop_abc1"),
                "internal key" to insert(1, "mer_01J9ZQG1"),
                "well formed" to insert(1, "goodshop01"),
            ) shouldBe mapOf(
                "revision 0 with a code" to 3819, "upper case" to 3819, "too short" to 3819, "leading digit" to 3819, "underscore" to 3819,
                "internal key" to 3819, "well formed" to null,
            )
        }
        Then("V36 SQL text: three statements with their expected ALGORITHM clause (INSTANT columns, COPY for the CHECK — L-14)") {
            val text = checkNotNull(javaClass.classLoader.getResource("db/migration/payment/V36__entitlement_head_merchant_code.sql")) { "V36 on the classpath" }.readText()
            val statements = text.lines().filterNot { it.trimStart().startsWith("--") }.joinToString("\n").split(';').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
            statements.map { s ->
                val head = when {
                    s.startsWith("ALTER TABLE entitlement_head ADD COLUMN merchant_code VARCHAR(14)") && s.contains(" NULL,") -> "head-column"
                    s.startsWith("ALTER TABLE entitlement_head ADD CONSTRAINT ck_entitlement_head_merchant_code CHECK") -> "head-check"
                    s.startsWith("ALTER TABLE idempotency_request ADD COLUMN result_merchant_code VARCHAR(14)") && s.contains(" NULL,") -> "idempotency-column"
                    else -> "unexpected"
                }
                head + ":" + Regex("ALGORITHM=[A-Z]+(, LOCK=[A-Z]+)?$").find(s)?.value
            } shouldBe listOf("head-column:ALGORITHM=INSTANT", "head-check:ALGORITHM=COPY", "idempotency-column:ALGORITHM=INSTANT")
        }
    }

    Given("MC-4-4 alert: a v4 code change at a higher revision for a merchant with a MID") {
        Then("it applies (normal update, the head carries the new code) and merchant_code_changed_with_mid is incremented once; a same-code revision is not counted") {
            val ids = SnapshotFixture.ids()
            val terms = SnapshotFixture.termsIds()
            send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 1), SnapshotFixture.leaseAt(T0))).status shouldBe 200
            val before = alerts()
            outcome(send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 2), SnapshotFixture.leaseAt(T0)))) shouldBe "APPLIED"
            alerts() shouldBe before
            outcome(send(mvc, body(SnapshotFixture.entitledWithCode(ids, terms, revision = 3, code = "othershop01"), SnapshotFixture.leaseAt(T0)))) shouldBe "APPLIED"
            code(ids) shouldBe "othershop01"
            alerts() shouldBe before + 1
            // 리뷰 MINOR-1: 코드가 사라지는 것(v4 head → 더 높은 revision의 v3 — MASTER 되돌림)도 같은 카운터로 센다(외부에 나간 코드의 소실 = 변경).
            outcome(send(mvc, body(SnapshotFixture.entitledWithChannels(ids, terms, revision = 4), SnapshotFixture.leaseAt(T0)))) shouldBe "APPLIED"
            code(ids) shouldBe null
            alerts() shouldBe before + 2
        }
    }
})
