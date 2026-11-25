package com.paycore.admin.merchantops

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionPolicy
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.approval.domain.OperatorCancelInputRules
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.BusinessPermissions
import com.paycore.admin.merchantops.application.BusinessProfileService
import com.paycore.admin.merchantops.application.RegistrationNumberBudget
import com.paycore.admin.merchantops.application.provided.BusinessCategoryInput
import com.paycore.admin.merchantops.application.provided.BusinessContactInput
import com.paycore.admin.merchantops.application.provided.BusinessFieldError
import com.paycore.admin.merchantops.application.provided.BusinessProfileContent
import com.paycore.admin.merchantops.application.provided.BusinessProfileOperation
import com.paycore.admin.merchantops.application.provided.BusinessProfileResult
import com.paycore.admin.merchantops.application.provided.BusinessRepresentativeInput
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.required.BusinessOpsMetrics
import com.paycore.admin.merchantops.application.required.MasterBusinessCommand
import com.paycore.admin.merchantops.application.required.MasterBusinessCommandResult
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.slf4j.LoggerFactory
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.AbstractPlatformTransactionManager
import org.springframework.transaction.support.DefaultTransactionStatus
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 사업자 명령 Use Case 판정·감사 표(BUSINESS-PROFILE-001 §7.1·§7.2·§7.4, §14 BIZ-3-1·2·3·4·5 — 대역 Port, Spring 없음):
 * 권한 상수 4종 값(D-BIZ-006 ①), 앞단(비활성·역할 없음 → 403 감사 0) / 권한 부족(APPROVER·VIEWER 단독 → 403 + `FORBIDDEN`), 형식 위반(create 400 + `INVALID_INPUT` 감사 —
 * D-MOPS-006 ④, update 400 감사 없음·지표), 등록 창·버킷(create만) 429, 정상 REQUESTED → MASTER 1회 → 결과, 중복 → existing 응답 + 감사 targetRef = 기존 businessId(D-BIZ-003)·버킷 확정,
 * MASTER 400 `BUSINESS_VALIDATION_FAILED` → 효과 없음 확정(fieldErrors·INVALID_INPUT 감사·drift 지표·버킷 환불 — OUTCOME_UNKNOWN 아님), MASTER 503 → RETRY_SAME_KEY·버킷 확정(m-1),
 * 감사 실패 경로, 같은 submissionId 재전송(같은 키·감사 2행씩), 사유는 `reason=PRESENT|ABSENT`만(C-13), 동시 create 20 → 버킷 10(BIZ-3-3). 각 행 MASTER 호출 수 단언.
 */
class BusinessProfileServiceSpec : BehaviorSpec({

    val operatorId = "adm_01JC00000000000000000000V1"
    val otherId = "adm_01JC00000000000000000000V2"
    val biz = "biz_01JC0000000000000000000001"
    val existingBiz = "biz_01JC0000000000000000000E01"
    val ctx = AdminRequestContext("req-1", "127.0.0.1")
    var subSeq = 0
    fun sub() = "%032x".format(++subSeq)

    class Fixture(roles: Set<String> = setOf("OPERATOR"), status: String = "ACTIVE", brnPerMinute: Int = 100, registrationsPerMinute: Int = 100, var now: Long = 0L) {
        val audits: MutableList<AuditRecord> = Collections.synchronizedList(mutableListOf())
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val commands: MutableList<MasterBusinessCommand> = Collections.synchronizedList(mutableListOf())
        val metrics: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val callInTx: MutableList<Boolean> = Collections.synchronizedList(mutableListOf())
        var failAuditAt: Int? = null
        var reply: (MasterBusinessCommand) -> MasterBusinessCommandResult = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000009", null, false, null) }
        val views = mutableMapOf(
            "adm_01JC00000000000000000000V1" to AdminSessionView("adm_01JC00000000000000000000V1", "Operator", status, 1, 1, roles),
            "adm_01JC00000000000000000000V2" to AdminSessionView("adm_01JC00000000000000000000V2", "Operator", status, 1, 1, roles),
        )
        val tx = object : AbstractPlatformTransactionManager() {
            override fun doGetTransaction(): Any = Any()
            override fun doBegin(transaction: Any, definition: TransactionDefinition) = Unit
            override fun doCommit(status: DefaultTransactionStatus) = Unit
            override fun doRollback(status: DefaultTransactionStatus) = Unit
        }
        val limits = object : MerchantOpsLimits {
            override val lookupPerMinute = 100
            override val registrationNumberSearchesPerMinute = brnPerMinute
            override val registrationsPerMinute = registrationsPerMinute
        }
        val budget = RegistrationNumberBudget(limits) { now }
        val gateway = object : MasterBusinessGateway {
            override fun search(criteria: BusinessSearchCriteria): MasterCallResult = error("not used")
            override fun lookup(registrationNumber: String): MasterCallResult = error("not used")
            override fun detail(businessId: String): MasterCallResult = error("not used")
            override fun contractIntakes(businessId: String): MasterCallResult = error("not used")
            override fun history(businessId: String, afterSeq: Long): MasterCallResult = error("not used")
            override fun command(command: MasterBusinessCommand): MasterBusinessCommandResult {
                callInTx += TransactionSynchronizationManager.isActualTransactionActive()
                events += "call"
                commands += command
                return reply(command)
            }
        }
        val service = BusinessProfileService(
            tx,
            object : AdminSessionQuery {
                override fun current(actorId: String) = views[actorId]
                override fun policy() = AdminSessionPolicy(Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1), Duration.ofMinutes(1))
            },
            object : AdminAuditRecorder {
                override fun record(record: AuditRecord) {
                    synchronized(audits) {
                        synchronized(events) {
                            if (failAuditAt == audits.size + events.count { it == "audit-failed" }) {
                                events += "audit-failed"
                                throw IllegalStateException("injected audit failure")
                            }
                            audits += record
                            events += "audit:${record.resultCode}"
                        }
                    }
                }
            },
            gateway,
            limits,
            object : MerchantOpsMetrics {
                override fun rateLimited(kind: String) {
                    metrics += "rate:$kind"
                }
                override fun command(operation: String, result: String) {
                    metrics += "command:$operation:$result"
                }
                override fun commandUnknown(operation: String) {
                    metrics += "unknown:$operation"
                }
            },
            object : BusinessOpsMetrics {
                override fun lookup(result: String) {
                    metrics += "lookup:$result"
                }
                override fun ruleDrift(operation: String) {
                    metrics += "drift:$operation"
                }
            },
            budget,
        )
        fun row(i: Int) = audits[i].let { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}|${it.correlationId}" }
        fun brnLeft(actor: String = "adm_01JC00000000000000000000V1"): Int = generateSequence { budget.admit(actor) }.takeWhile { it }.count()
    }

    val existing = linkedMapOf<String, Any?>("businessId" to existingBiz, "businessName" to "기존 사업자", "registrationNumber" to "9120000001", "registrationStatus" to "DRAFT")

    Given("BIZ-3-2 permission constants (D-BIZ-006 ① — 사용자 결정 2026-11-17, m-6 four separate constants)") {
        Then("VIEW = {OPERATOR, APPROVER}, CREATE = UPDATE = COMPLETE = {OPERATOR}") {
            BusinessPermissions.VIEW shouldBe setOf("OPERATOR", "APPROVER")
            BusinessPermissions.CREATE shouldBe setOf("OPERATOR")
            BusinessPermissions.UPDATE shouldBe setOf("OPERATOR")
            BusinessPermissions.COMPLETE shouldBe setOf("OPERATOR")
        }
        Then("D-BIZ-003 invariant: every role that may create may also view (CREATE ⊆ VIEW) — the duplicate `existing` view-permission filter is kept as code (§7.3) but is unreachable today") {
            // 이 불변식이 깨지면(생성 가능 + 조회 불가 역할) BusinessProfileService의 existing 필터가 실제로 작동해야 한다 — 그때 그 역할로 existing 미노출 시험을 추가한다.
            BusinessPermissions.VIEW.containsAll(BusinessPermissions.CREATE) shouldBe true
        }
    }

    Given("BIZ-3-1 the front gate and the permission constants") {
        Then("no session, an inactive account or an account without any role → 403 with no audit row and no MASTER call") {
            listOf(Fixture(status = "SUSPENDED"), Fixture(roles = emptySet()), Fixture(roles = setOf("UNMASK"))).forEach { f ->
                f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz), ctx) shouldBe BusinessProfileResult.Forbidden
                f.audits.shouldBeEmpty()
                f.commands.size shouldBe 0
            }
            Fixture().let { f ->
                f.service.execute("adm_01JC0000000000000000000NOP", BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.Forbidden
                f.audits.shouldBeEmpty()
            }
        }
        Then("APPROVER alone (D-BIZ-006 ① — view only) and VIEWER get 403 + FORBIDDEN for create, update and registration; MASTER is not called") {
            listOf(setOf("APPROVER"), setOf("VIEWER"), setOf("SECURITY_ADMIN")).forEach { roles ->
                val f = Fixture(roles = roles)
                val number = BusinessInputFixture.randomNumber()
                val s1 = sub()
                f.service.execute(operatorId, BusinessInputFixture.create(s1, number = number), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, version = 3, reason = "사유"), ctx) shouldBe BusinessProfileResult.Forbidden
                f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz), ctx) shouldBe BusinessProfileResult.Forbidden
                f.commands.size shouldBe 0
                withClue(roles) {
                    f.audits.map { "${it.action}|${it.resultCode}|${it.targetRef}" } shouldBe listOf(
                        "BUSINESS_CREATE|FORBIDDEN|null", "BUSINESS_UPDATE|FORBIDDEN|$biz", "BUSINESS_REGISTRATION_COMPLETE|FORBIDDEN|$biz",
                    )
                    f.audits[0].detail shouldBe "sub=${s1.take(8)};status=REGISTERED;brn=${number.take(3)}*******"
                    f.audits[1].detail!!.endsWith(";version=3;reason=PRESENT") shouldBe true
                    f.audits.forEach { it.correlationId shouldBe "req-1" }
                }
            }
        }
        Then("OPERATOR passes all three (one MASTER call each)") {
            val f = Fixture()
            f.reply = { c -> MasterBusinessCommandResult.Decided(true, if (c.operation == BusinessProfileOperation.CREATE) "biz_01JC0000000000000000000009" else biz, null, false, null) }
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.commands.map { it.operation } shouldBe listOf(BusinessProfileOperation.CREATE, BusinessProfileOperation.UPDATE, BusinessProfileOperation.COMPLETE_REGISTRATION)
        }
    }

    Given("BIZ-3-1 format (BusinessProfileInputRules — MASTER §4.2·§4.3 replica, C-10)") {
        Then("create violation → 400 + fieldErrors + audit INVALID_INPUT (masked brn, or brn=INVALID for a malformed number); no MASTER call, no bucket use") {
            val f = Fixture(brnPerMinute = 3)
            val number = BusinessInputFixture.randomNumber()
            val bad = BusinessInputFixture.individualRegistered(name = " 앞공백")
            f.service.execute(operatorId, BusinessInputFixture.create(sub(), number = number, content = bad), ctx) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("displayName", "FORMAT")))
            f.service.execute(operatorId, BusinessInputFixture.create(sub(), number = "123-45-67890"), ctx) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("registrationNumber", "FORMAT")))
            f.audits.map { "${it.action}|${it.resultCode}|${it.detail!!.substringAfterLast(';')}" } shouldBe listOf(
                "BUSINESS_CREATE|INVALID_INPUT|brn=${number.take(3)}*******", "BUSINESS_CREATE|INVALID_INPUT|brn=INVALID",
            )
            f.commands.size shouldBe 0
            f.brnLeft() shouldBe 3
        }
        Then("update / registration violation → 400 with no audit row; metric only") {
            val f = Fixture()
            val noContacts = BusinessInputFixture.individualRegistered().let {
                BusinessProfileContent(it.businessType, it.displayName, it.registration, it.representatives, it.categories, it.addresses, emptyList())
            }
            // update = DRAFT 공통 규칙(E-10 ④): 담당자 0명은 통과 — 대조로 등록 완료(REGISTERED 규칙)에서만 REQUIRED.
            f.reply = { MasterBusinessCommandResult.Decided(true, biz, null, false, null) }
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, content = noContacts), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            val before = f.audits.size
            f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz, content = noContacts), ctx) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("contacts", "REQUIRED")))
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, reason = "줄\n바꿈"), ctx) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("reason", "FORMAT")))
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), "biz_bad"), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.service.execute(operatorId, BusinessInputFixture.update("not-hex", biz), ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.audits.size shouldBe before
            f.commands.size shouldBe 1
            f.metrics.count { it == "command:CompleteBusinessRegistration:INVALID_INPUT" } shouldBe 1
            f.metrics.count { it == "command:UpdateBusiness:INVALID_INPUT" } shouldBe 3
        }
        Then("SEC-BIZ-001 M-1 (BR-BIZ-305): a card-number-like reason is 400 [reason CARD_NUMBER_PATTERN] before MASTER (no call, no audit); the verdict equals the canonical OperatorCancelInputRules.containsCardNumber on the common vector") {
            // 정본 대조: 공통 벡터가 정본 판정과 같은 결과인지 먼저 고정(벡터가 정본에서 벗어나면 여기서 RED).
            BusinessCardNumberVectors.CARD_LIKE.map(OperatorCancelInputRules::containsCardNumber) shouldBe BusinessCardNumberVectors.CARD_LIKE.map { true }
            BusinessCardNumberVectors.NOT_CARD.map(OperatorCancelInputRules::containsCardNumber) shouldBe BusinessCardNumberVectors.NOT_CARD.map { false }
            val f = Fixture()
            val before = f.audits.size
            BusinessCardNumberVectors.CARD_LIKE.forEach { reason ->
                f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, reason = reason), ctx) shouldBe
                    BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("reason", OperatorCancelInputRules.CARD_NUMBER_PATTERN)))
                f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz, reason = reason), ctx) shouldBe
                    BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("reason", OperatorCancelInputRules.CARD_NUMBER_PATTERN)))
            }
            f.commands.size shouldBe 0
            f.audits.size shouldBe before
            f.reply = { MasterBusinessCommandResult.Decided(true, biz, null, false, null) }
            BusinessCardNumberVectors.NOT_CARD.forEach { reason ->
                f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, reason = reason), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            }
            f.commands.size shouldBe BusinessCardNumberVectors.NOT_CARD.size
            // 기존 문자 규칙이 먼저(FORMAT·TOO_LONG — 카드 패턴은 마지막 판정).
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, reason = "4111111111111111" + "가".repeat(185)), ctx) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("reason", "TOO_LONG")))
        }
        Then("rule table (MASTER parity): categories 11+9 → categories TOO_MANY (MINOR-2), representative type path representatives[i].type, SOLE with others, duplicate category, individual with corporate number") {
            fun errors(content: BusinessProfileContent, status: String = "REGISTERED") = Fixture().service.execute(operatorId, BusinessInputFixture.create(sub(), status, content = content), ctx)
            val base = BusinessInputFixture.individualRegistered()
            fun variant(reps: List<BusinessRepresentativeInput> = base.representatives, cats: List<BusinessCategoryInput> = base.categories, contacts: List<BusinessContactInput> = base.contacts,
                corporate: String? = null) =
                BusinessProfileContent(base.businessType, base.displayName, base.registration.let {
                    com.paycore.admin.merchantops.application.provided.BusinessRegistrationInput(corporate, it.openingDate, it.countryCode, it.englishName, it.phoneNumber, it.email)
                }, reps, cats, base.addresses, contacts)
            val manyTypes = (1..11).map { BusinessCategoryInput("BUSINESS_TYPE", "업태$it") } + (1..9).map { BusinessCategoryInput("BUSINESS_ITEM", "종목$it") }
            errors(variant(cats = manyTypes)) shouldBe BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("categories", "TOO_MANY")))
            errors(variant(reps = listOf(BusinessRepresentativeInput("갑", "JOINT"), BusinessRepresentativeInput("을", "PARTNER")))) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("representatives[1].type", "FORMAT")))
            errors(variant(reps = listOf(BusinessRepresentativeInput("갑", "SOLE"), BusinessRepresentativeInput("을", "JOINT")))) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("representatives", "SOLE_WITH_OTHERS")))
            // m-5: 같은 이름·유형 대표자 두 행은 허용(대조 — 업태 같은 이름은 DUPLICATE).
            (errors(variant(reps = listOf(BusinessRepresentativeInput("갑", "JOINT"), BusinessRepresentativeInput("갑", "JOINT")))) is BusinessProfileResult.Decided) shouldBe true
            errors(variant(cats = base.categories + BusinessCategoryInput("BUSINESS_TYPE", "도소매"))) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("categories[2].name", "DUPLICATE")))
            errors(variant(corporate = "1101110000000")) shouldBe BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("registration.corporateRegistrationNumber", "NOT_ALLOWED")))
            errors(variant(contacts = base.contacts.map { BusinessContactInput(it.name, it.email, it.phoneNumber, false) })) shouldBe
                BusinessProfileResult.InvalidInput(listOf(BusinessFieldError("contacts", "PRIMARY_REQUIRED")))
            // D-BIZ-002 DRAFT 최소: 구분·번호·상호만으로 DRAFT 통과, 같은 내용 REGISTERED는 필수 7항목.
            (errors(BusinessInputFixture.draftMinimal(), "DRAFT") is BusinessProfileResult.Decided) shouldBe true
            (errors(BusinessInputFixture.draftMinimal()) as BusinessProfileResult.InvalidInput).fieldErrors.map { it.path } shouldBe listOf(
                "addresses", "categories", "contacts", "registration.openingDate", "registration.phoneNumber", "representatives",
            )
        }
    }

    Given("BIZ-3-1 windows and the shared registration-number bucket") {
        Then("registration window exceeded → 429 + RATE_LIMITED, no MASTER call (window shared by the three operations of this service)") {
            val f = Fixture(registrationsPerMinute = 2)
            f.reply = { MasterBusinessCommandResult.Decided(true, biz, null, false, null) }
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.RateLimited
            f.commands.size shouldBe 2
            f.audits.last().resultCode shouldBe "RATE_LIMITED"
            f.metrics shouldContain "rate:REGISTRATION"
        }
        Then("create only reserves the bucket: bucket exhausted → 429 + RATE_LIMITED for create while update still passes") {
            val f = Fixture(brnPerMinute = 1)
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>() // 성공은 환불 → 다시 1회.
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing) }
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>() // 중복 → 확정.
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.RateLimited
            f.metrics shouldContain "rate:REGISTRATION_NUMBER"
            f.reply = { MasterBusinessCommandResult.Decided(true, biz, null, false, null) }
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz), ctx).shouldBeInstanceOf<BusinessProfileResult.Decided>()
            f.commands.size shouldBe 4
        }
        Then("BIZ-3-3 twenty concurrent creates against a bucket of 10 → exactly 10 reach MASTER (atomic reserve, duplicate results keep their reservation)") {
            val f = Fixture(brnPerMinute = 10)
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing) }
            val pool = Executors.newFixedThreadPool(20)
            val barrier = CyclicBarrier(20)
            val requests = (1..20).map { BusinessInputFixture.create(sub()) }
            val results = requests.map { r ->
                pool.submit<BusinessProfileResult> { barrier.await(10, TimeUnit.SECONDS); f.service.execute(operatorId, r, ctx) }
            }.map { it.get(30, TimeUnit.SECONDS) }
            pool.shutdown()
            results.size shouldBe 20
            f.commands.size shouldBe 10
            results.count { it == BusinessProfileResult.RateLimited } shouldBe 10
            f.brnLeft() shouldBe 0
        }
    }

    Given("BIZ-3-1 a normal command") {
        Then("REQUESTED → one MASTER call outside any TX → result row; key mo1.<ULID>.<sub>, actor admin:<id>, correlation mos.<sub>; MASTER body has every key (optional null) in MASTER order") {
            val f = Fixture()
            val s = sub()
            val number = BusinessInputFixture.randomNumber()
            f.service.execute(operatorId, BusinessInputFixture.create(s, number = number), ctx) shouldBe
                BusinessProfileResult.Decided("SUCCEEDED", "biz_01JC0000000000000000000009", null, false, null)
            f.events shouldBe listOf("audit:REQUESTED", "call", "audit:OK")
            f.callInTx shouldBe listOf(false)
            val c = f.commands.single()
            listOf(c.idempotencyKey, c.actor, c.correlationId) shouldBe listOf("mo1.01JC00000000000000000000V1.$s", "admin:$operatorId", "mos.$s")
            c.body.keys.toList() shouldBe listOf(
                "registrationStatus", "registrationNumber", "businessType", "displayName", "registration", "representatives", "categories", "addresses", "contacts",
            )
            c.body["registrationNumber"] shouldBe number
            (c.body["registration"] as Map<*, *>).let { it.containsKey("corporateRegistrationNumber") && it["corporateRegistrationNumber"] == null } shouldBe true
            f.row(0) shouldBe "BUSINESS_CREATE|REQUESTED|sub=${s.take(8)};status=REGISTERED;brn=${number.take(3)}*******|null|mos.$s"
            f.row(1) shouldBe "BUSINESS_CREATE|OK|sub=${s.take(8)};replayed=false;status=REGISTERED;brn=${number.take(3)}*******|biz_01JC0000000000000000000009|mos.$s"
        }
        Then("update body: businessId·version·reason then content (no registrationNumber/status); audit reason=PRESENT|ABSENT only — the reason text is in no audit field (C-13)") {
            val f = Fixture()
            f.reply = { MasterBusinessCommandResult.Decided(true, biz, null, false, null) }
            val s = sub()
            f.service.execute(operatorId, BusinessInputFixture.update(s, biz, version = 7, reason = BusinessInputFixture.CANARY_REASON), ctx)
            f.service.execute(operatorId, BusinessInputFixture.complete(sub(), biz, version = 8), ctx)
            f.commands[0].body.keys.toList() shouldBe listOf(
                "businessId", "version", "reason", "businessType", "displayName", "registration", "representatives", "categories", "addresses", "contacts",
            )
            f.commands[0].body["version"] shouldBe 7L
            f.row(1) shouldBe "BUSINESS_UPDATE|OK|sub=${s.take(8)};replayed=false;version=7;reason=PRESENT|$biz|mos.$s"
            f.audits[3].detail!!.endsWith("version=8;reason=ABSENT") shouldBe true
            f.audits.forEach { a -> listOf(a.detail, a.targetRef, a.correlationId).joinToString("|").contains(BusinessInputFixture.CANARY_REASON) shouldBe false }
        }
        Then("an update success whose reference is another business is not this request's result → OUTCOME_UNKNOWN (fail-closed)") {
            val f = Fixture()
            f.reply = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000002", null, false, null) }
            f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz), ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.audits.last().resultCode shouldBe "OUTCOME_UNKNOWN"
        }
    }

    Given("BIZ-3-1 duplicate registration number (D-BIZ-003 — 사용자 결정 2026-11-17, C-14)") {
        Then("D-BIZ-003 the response carries existing (상호·원문 번호(D-BIZ-007)·내부 키·등록 상태); audit targetRef = the existing businessId; the bucket stays consumed") {
            val f = Fixture(brnPerMinute = 5)
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing) }
            val s = sub()
            f.service.execute(operatorId, BusinessInputFixture.create(s), ctx) shouldBe
                BusinessProfileResult.Decided("REJECTED", null, "BUSINESS_REGISTRATION_NUMBER_DUPLICATE", false, existing)
            f.audits.last().let { listOf(it.resultCode, it.targetRef) } shouldBe listOf("REJECTED", existingBiz)
            f.audits.last().detail!!.contains("rejection=BUSINESS_REGISTRATION_NUMBER_DUPLICATE") shouldBe true
            f.brnLeft() shouldBe 4
        }
        Then("another rejection has no existing and no targetRef for create; the bucket is refunded") {
            val f = Fixture(brnPerMinute = 5)
            f.reply = { MasterBusinessCommandResult.Decided(false, null, "BUSINESS_VERSION_CONFLICT", true, null) }
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.Decided("REJECTED", null, "BUSINESS_VERSION_CONFLICT", true, null)
            f.audits.last().targetRef shouldBe null
            f.brnLeft() shouldBe 5
        }
    }

    Given("BIZ-3-4 failure paths (BIZ-2 인계 ②③)") {
        Then("인계 ② MASTER 400 BUSINESS_VALIDATION_FAILED → no effect confirmed: 400 with the same fieldErrors, audit INVALID_INPUT (not OUTCOME_UNKNOWN), drift metric, bucket refunded") {
            val f = Fixture(brnPerMinute = 5)
            val errors = listOf(BusinessFieldError("categories", "TOO_MANY"), BusinessFieldError("representatives[0].type", "FORMAT"))
            f.reply = { MasterBusinessCommandResult.Invalid(errors) }
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.InvalidInput(errors)
            f.audits.map { it.resultCode } shouldBe listOf("REQUESTED", "INVALID_INPUT")
            f.metrics shouldContain "drift:CreateBusiness"
            f.metrics.none { it.startsWith("unknown:") } shouldBe true
            f.brnLeft() shouldBe 5
        }
        Then("인계 ③ MASTER 503 (TEMPORARILY_UNAVAILABLE incl. the existing-summary failure, m-1) → MASTER_RETRY_SAME_KEY, audit OUTCOME_UNKNOWN, bucket kept (no refund)") {
            val f = Fixture(brnPerMinute = 5)
            f.reply = { MasterBusinessCommandResult.RetrySameKey("TEMPORARILY_UNAVAILABLE") }
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.RetrySameKey
            f.audits.last().resultCode shouldBe "OUTCOME_UNKNOWN"
            f.brnLeft() shouldBe 4
        }
        Then("timeout / unreadable → OUTCOME_UNKNOWN (bucket kept); Busy → MASTER_BUSY (kept); key reuse → KEY_REUSED (refunded)") {
            listOf(
                MasterBusinessCommandResult.Unknown("TIMEOUT") to (BusinessProfileResult.OutcomeUnknown to 4),
                MasterBusinessCommandResult.Unknown("RESPONSE_UNREADABLE") to (BusinessProfileResult.OutcomeUnknown to 4),
                MasterBusinessCommandResult.Busy("ADMIN_WRITE_BUSY") to (BusinessProfileResult.MasterBusy to 4),
                MasterBusinessCommandResult.KeyReused to (BusinessProfileResult.KeyReused to 5),
            ).forEach { (reply, expected) ->
                val f = Fixture(brnPerMinute = 5)
                f.reply = { reply }
                withClue(reply) {
                    f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe expected.first
                    f.brnLeft() shouldBe expected.second
                }
            }
        }
        Then("attempt audit failure → AUDIT_UNAVAILABLE, MASTER not called, bucket refunded; result audit failure → AUDIT_UNAVAILABLE (MASTER effect may exist)") {
            val f = Fixture(brnPerMinute = 5)
            f.failAuditAt = 0
            f.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.AuditUnavailable
            f.commands.size shouldBe 0
            f.brnLeft() shouldBe 5
            val g = Fixture()
            g.failAuditAt = 1
            g.service.execute(operatorId, BusinessInputFixture.create(sub()), ctx) shouldBe BusinessProfileResult.AuditUnavailable
            g.commands.size shouldBe 1
            g.metrics shouldContain "command:CreateBusiness:AUDIT_UNAVAILABLE"
        }
    }

    Given("BIZ-3-5 resend with the same submissionId") {
        Then("same MASTER key and correlation; the replayed result is returned; each attempt adds its own two audit rows (REQUESTED + result)") {
            val f = Fixture()
            val s = sub()
            val request = BusinessInputFixture.create(s)
            f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
            f.service.execute(operatorId, request, ctx) shouldBe BusinessProfileResult.OutcomeUnknown
            f.reply = { MasterBusinessCommandResult.Decided(true, "biz_01JC0000000000000000000009", null, true, null) }
            f.service.execute(operatorId, request, ctx) shouldBe BusinessProfileResult.Decided("SUCCEEDED", "biz_01JC0000000000000000000009", null, true, null)
            f.commands.map { it.idempotencyKey }.distinct() shouldBe listOf("mo1.01JC00000000000000000000V1.$s")
            f.commands[0].body shouldBe f.commands[1].body
            f.audits.map { "${it.resultCode}|${it.correlationId}" } shouldBe listOf("REQUESTED|mos.$s", "OUTCOME_UNKNOWN|mos.$s", "REQUESTED|mos.$s", "OK|mos.$s")
        }
        Then("C-4 another operator's same submissionId maps to a different MASTER key") {
            val f = Fixture()
            val s = sub()
            f.service.execute(operatorId, BusinessInputFixture.create(s), ctx)
            f.service.execute(otherId, BusinessInputFixture.create(s), ctx)
            f.commands.map { it.idempotencyKey } shouldBe listOf("mo1.01JC00000000000000000000V1.$s", "mo1.01JC00000000000000000000V2.$s")
        }
    }

    Given("malformed bodies (rejectMalformed)") {
        Then("create → audit brn=INVALID (INVALID_INPUT or FORBIDDEN by role); update / registration → 400 without audit unless forbidden") {
            val f = Fixture()
            f.service.rejectMalformed(operatorId, BusinessProfileOperation.CREATE, null, ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.service.rejectMalformed(operatorId, BusinessProfileOperation.UPDATE, biz, ctx) shouldBe BusinessProfileResult.InvalidInput()
            f.audits.map { "${it.action}|${it.resultCode}|${it.detail}" } shouldBe listOf("BUSINESS_CREATE|INVALID_INPUT|brn=INVALID")
            val a = Fixture(roles = setOf("APPROVER"))
            a.service.rejectMalformed(operatorId, BusinessProfileOperation.CREATE, null, ctx) shouldBe BusinessProfileResult.Forbidden
            a.service.rejectMalformed(operatorId, BusinessProfileOperation.COMPLETE_REGISTRATION, biz, ctx) shouldBe BusinessProfileResult.Forbidden
            a.audits.map { "${it.action}|${it.resultCode}|${it.detail}|${it.targetRef}" } shouldBe listOf(
                "BUSINESS_CREATE|FORBIDDEN|brn=INVALID|null", "BUSINESS_REGISTRATION_COMPLETE|FORBIDDEN|null|$biz",
            )
            Fixture(roles = emptySet()).let { n ->
                n.service.rejectMalformed(operatorId, BusinessProfileOperation.CREATE, null, ctx) shouldBe BusinessProfileResult.Forbidden
                n.audits.shouldBeEmpty()
            }
        }
    }

    Given("BIZ-3-6 service logs") {
        Then("no canary value (number, names, email, phone, address, reason, 상호) and no MASTER key in any log line of a full command cycle") {
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            root.addAppender(appender)
            val s = sub()
            try {
                val f = Fixture()
                f.service.execute(operatorId, BusinessInputFixture.create(s, number = BusinessInputFixture.CANARY_NUMBER, content = BusinessInputFixture.canaryRegistered()), ctx)
                f.reply = { MasterBusinessCommandResult.Unknown("TIMEOUT") }
                f.service.execute(operatorId, BusinessInputFixture.update(sub(), biz, reason = BusinessInputFixture.CANARY_REASON, content = BusinessInputFixture.canaryRegistered()), ctx)
                f.service.execute(operatorId, BusinessInputFixture.create(sub(), number = BusinessInputFixture.CANARY_NUMBER,
                    content = BusinessInputFixture.individualRegistered(name = " " + BusinessInputFixture.CANARY_NAME)), ctx)
            } finally {
                root.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            lines.any { "operation=business-profile" in it } shouldBe true
            lines.forEach { line ->
                listOf(
                    BusinessInputFixture.CANARY_NUMBER, BusinessInputFixture.CANARY_REPRESENTATIVE, BusinessInputFixture.CANARY_EMAIL, BusinessInputFixture.CANARY_PHONE,
                    BusinessInputFixture.CANARY_DETAIL_ADDRESS, BusinessInputFixture.CANARY_REASON, BusinessInputFixture.CANARY_NAME, "mo1.",
                ).forEach { canary -> withClue(line) { line.contains(canary) shouldBe false } }
            }
        }
    }
})
