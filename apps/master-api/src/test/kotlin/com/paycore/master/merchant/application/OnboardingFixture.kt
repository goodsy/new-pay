package com.paycore.master.merchant.application

import com.paycore.master.MasterDbAccounts
import com.paycore.master.merchant.application.provided.AddServiceAgreementCommand
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminIdempotencyKey
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApproveContractRevisionCommand
import com.paycore.master.merchant.application.provided.IssueFirstDefaultMerchantAccountCommand
import com.paycore.master.merchant.application.provided.MerchantOnboardingUseCase
import com.paycore.master.merchant.application.provided.RegisterBusinessCommand
import com.paycore.master.merchant.application.provided.RegisterContractCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantCommand
import com.paycore.master.merchant.application.provided.RegisterMerchantSiteCommand
import com.paycore.master.merchant.application.provided.ScopeInput
import com.paycore.master.merchant.domain.ActorRef
import com.paycore.master.merchant.domain.BusinessId
import com.paycore.master.merchant.domain.BusinessRegistrationNumber
import com.paycore.master.merchant.domain.ChannelType
import com.paycore.master.merchant.domain.ContractId
import com.paycore.master.merchant.domain.ContractServiceAgreementId
import com.paycore.master.merchant.domain.DisplayName
import com.paycore.master.merchant.domain.EffectivePeriod
import com.paycore.master.merchant.domain.MerchantId
import com.paycore.master.merchant.domain.MerchantSiteId
import com.paycore.master.merchant.domain.ServiceKind
import io.kotest.matchers.types.shouldBeInstanceOf
import org.testcontainers.mysql.MySQLContainer
import java.security.SecureRandom
import java.sql.Connection
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 로그 수집(Logback ListAppender, root). 민감값(등록번호·채널 식별값)이 어떤 로그 줄·예외 메시지에도 나오지 않는지 확인하는 데 쓴다(review M-2).
 */
class CapturedLogs : AutoCloseable {
    private val appender = ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>().also { it.start() }
    private val root = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger

    init {
        root.addAppender(appender)
    }

    /** 각 이벤트의 로거 이름·형식화된 메시지·예외 사슬 메시지. */
    fun lines(): List<String> = appender.list.toList().map { e ->
        buildString {
            append(e.loggerName).append(" ").append(e.formattedMessage)
            var t: ch.qos.logback.classic.spi.IThrowableProxy? = e.throwableProxy
            while (t != null) {
                append(" | ").append(t.className).append(": ").append(t.message)
                t = t.cause
            }
        }
    }

    override fun close() {
        root.detachAppender(appender)
        appender.stop()
    }
}

/**
 * application·통합 시험의 Named Fixture(src/test 전용). 모든 준비 데이터는 **공식 use case**(관리 명령)로 만든다(행을 직접 넣지 않는다).
 * 등록번호·채널은 시험 값이며 실제 사업자·도메인이 아니다. 시각은 명령마다 서버 clock 대신 명시 값을 쓴다(재현성).
 */
object OnboardingFixture {
    private val random = SecureRandom()
    val operator = ActorRef("operator-1")
    val t0: Instant = Instant.parse("2026-10-09T02:00:00.000Z")

    fun key(): AdminIdempotencyKey = AdminIdempotencyKey("k-" + UUID.randomUUID())

    /** 명령 시각은 요청에 없다(서버 `MasterClock` — 시험에서는 [MutableMasterClock], 기본 [t0]). */
    fun request(key: AdminIdempotencyKey = key(), actor: ActorRef = operator) = AdminRequest(key, actor, "corr-" + UUID.randomUUID())

    /**
     * D-MOPS-008 ② / MERCHANT-OPS-002 C-26: 근거 없는 계약 승인·첫 MID 발급은 Demo seed 경로 전용(행위자 정확히 `system:demo-seed` + Demo 모드)이 됐다. 시험 준비에서 그 두 명령을 부를 때만
     * 이 요청을 쓴다(@Primary `SwitchableDeploymentMode` = Demo). 다른 명령의 행위자는 그대로 [operator]다.
     */
    val seedActor = ActorRef("system:demo-seed")

    fun seedRequest(key: AdminIdempotencyKey = key()) = request(key, seedActor)

    fun registrationNumber() = BusinessRegistrationNumber((1..10).map { random.nextInt(10) }.joinToString(""))

    fun host() = "shop-" + UUID.randomUUID().toString().substring(0, 12) + ".example.test"

    /** 공식 명령으로 만든 Merchant 구조: Business → Merchant → Site 2개 → 계약(MERCHANT Scope, Revision 1 승인) → 카드 CSA. MID는 아직 없다. */
    data class ReadyMerchant(
        val businessId: BusinessId,
        val merchantId: MerchantId,
        val siteId: MerchantSiteId,
        val secondSiteId: MerchantSiteId,
        val contractId: ContractId,
        val agreementId: ContractServiceAgreementId,
    ) {
        fun issueCommand(key: AdminIdempotencyKey = key(), site: MerchantSiteId = siteId, revisionNo: Int = 1) =
            IssueFirstDefaultMerchantAccountCommand(seedRequest(key), merchantId, agreementId, contractId, revisionNo, site) // D-MOPS-008 ②: seed 경로 행위자
    }

    fun succeeded(result: AdminCommandResult): String = result.shouldBeInstanceOf<AdminCommandResult.Succeeded>().reference

    /** [contractActor]: 계약(Revision 1) 등록자 — B2-3 "등록자 = 승인자여도 MID 발급은 통과" 시험이 승인자로 바꾼다(기본 [operator] — 기존 시험 불변). */
    fun readyMerchant(service: MerchantOnboardingUseCase, approved: Boolean = true, contractActor: ActorRef = operator): ReadyMerchant {
        val business = BusinessId(succeeded(service.registerBusiness(RegisterBusinessCommand(request(), registrationNumber(), DisplayName("Demo Business")))))
        val merchant = MerchantId(succeeded(service.registerMerchant(RegisterMerchantCommand(request(), business, DisplayName("Demo Merchant"), com.paycore.master.merchant.domain.MerchantCodeFixtures.unique()))))
        val site = MerchantSiteId(succeeded(service.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, host()))))
        val second = MerchantSiteId(succeeded(service.registerMerchantSite(RegisterMerchantSiteCommand(request(), merchant, ChannelType.WEB, host()))))
        val contract = ContractId(
            succeeded(service.registerContract(RegisterContractCommand(request(actor = contractActor), business, ScopeInput(merchant), EffectivePeriod(t0)))),
        )
        if (approved) succeeded(service.approveContractRevision(ApproveContractRevisionCommand(seedRequest(), contract, 1))) // D-MOPS-008 ②: seed 경로 행위자
        val agreement = ContractServiceAgreementId(
            succeeded(service.addServiceAgreement(AddServiceAgreementCommand(request(), contract, ServiceKind.CARD_PG_PAYMENT))),
        )
        return ReadyMerchant(business, merchant, site, second, contract, agreement)
    }

    /** 한 Merchant에 관한 MASTER 행 수(runtime 계정 — 운영과 같은 권한으로 읽는다). */
    data class MerchantRows(val accounts: Long, val defaults: Long, val assignments: Long, val outbox: Long, val history: Long, val entitlement: Long)

    fun rowsOf(container: MySQLContainer, merchantId: MerchantId): MerchantRows = runtime(container).use { c ->
        fun count(sql: String) = c.prepareStatement(sql).use { ps ->
            ps.setString(1, merchantId.value)
            ps.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
        }
        MerchantRows(
            accounts = count("SELECT COUNT(*) FROM merchant_account WHERE merchant_id = ?"),
            defaults = count("SELECT COUNT(*) FROM default_designation WHERE merchant_id = ?"),
            assignments = count("SELECT COUNT(*) FROM merchant_account_site_assignment WHERE merchant_id = ?"),
            outbox = count(
                "SELECT COUNT(*) FROM outbox_event WHERE event_type IN ('MerchantAccountIssued', 'DefaultMerchantAccountDesignated', " +
                    "'SiteAssignmentStarted') AND JSON_UNQUOTE(JSON_EXTRACT(payload, '$.merchantId')) = ?",
            ),
            history = count(
                "SELECT COUNT(*) FROM master_change_history WHERE change_type IN ('MerchantAccountIssued', 'DefaultMerchantAccountDesignated', " +
                    "'SiteAssignmentStarted') AND JSON_UNQUOTE(JSON_EXTRACT(detail, '$.merchantId')) = ?",
            ),
            entitlement = count("SELECT entitlement_revision FROM merchant WHERE merchant_id = ?"),
        )
    }

    fun runtime(container: MySQLContainer): Connection =
        MasterDbAccounts.connect(container, MasterDbAccounts.MASTER_DATABASE, MasterDbAccounts.MASTER_RUNTIME_USER)

    /** [n]개 스레드가 같은 출발선에서 [block]을 실행한다(각자 자기 DB 연결·로컬 TX). */
    fun <T> concurrently(n: Int, block: (Int) -> T): List<Result<T>> {
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        try {
            val futures = (0 until n).map { i -> pool.submit<Result<T>> { start.await(); runCatching { block(i) } } }
            start.countDown()
            return futures.map { it.get(60, TimeUnit.SECONDS) }
        } finally {
            pool.shutdownNow()
        }
    }
}
