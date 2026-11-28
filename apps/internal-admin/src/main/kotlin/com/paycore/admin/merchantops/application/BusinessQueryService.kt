package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.merchantops.application.provided.BusinessPendingApprovals
import com.paycore.admin.merchantops.application.provided.BusinessQueryResult
import com.paycore.admin.merchantops.application.provided.BusinessQueryUseCase
import com.paycore.admin.merchantops.application.provided.BusinessSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterBusinessGateway
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle

/**
 * [BusinessQueryUseCase](BUSINESS-PROFILE-001 §7.2). 순서 = 앞단(감사 없음) → 권한 [BusinessPermissions.VIEW](D-BIZ-006 ①) → 형식(MASTER `BusinessSearchRequestParser` 규칙 복제 — 400, MASTER 미호출)
 * → 사업자 조회 창([BusinessReadWindow] — 번호 조회와 공유, m-2) → MASTER(TX 밖) → (상세만) 감사 `BUSINESS_VIEWED` → 반환.
 * - 상세(MERCHANT-FLOW-001 FLOW-1): 찾은 상세에는 '계약 진행' 섹션([BusinessContractProgress] — 키 `contractProgress`)을 붙인다. 섹션 조회 실패는 상세를 실패시키지 않는다.
 * - 상세(D-BIZ-006 ②): 개인정보 원문을 돌려주므로 **열람마다 반환 전 감사**(OK·NOT_FOUND·UNAVAILABLE), 역할 부족 `FORBIDDEN`·창 초과 `RATE_LIMITED`도 1행(열람 시도 기록). 감사 실패 →
 *   [BusinessQueryResult.AuditUnavailable](결과 미반환). targetRef = businessId(형식이 맞을 때).
 * - 목록·이력: 감사 없음(D-MOPS-002), 창 초과는 지표 `rate_limited{kind=BUSINESS_READ}`만.
 * 로그: 연산·결과 분류만(상호 접두·번호·이름 없음).
 */
@Service
class BusinessQueryService internal constructor(
    transactionManager: PlatformTransactionManager,
    sessions: AdminSessionQuery,
    recorder: AdminAuditRecorder,
    private val gateway: MasterBusinessGateway,
    private val window: BusinessReadWindow,
    private val metrics: MerchantOpsMetrics,
    clock: () -> Instant,
    private val pendingChanges: BusinessPendingApprovals?,
) : BusinessQueryUseCase {

    @Autowired
    constructor(
        transactionManager: PlatformTransactionManager,
        sessions: AdminSessionQuery,
        recorder: AdminAuditRecorder,
        gateway: MasterBusinessGateway,
        window: BusinessReadWindow,
        metrics: MerchantOpsMetrics,
        pendingChanges: BusinessPendingApprovals,
    ) : this(transactionManager, sessions, recorder, gateway, window, metrics, Instant::now, pendingChanges)

    private val audit = BusinessAudit(transactionManager, sessions, recorder)
    private val contractProgress = BusinessContractProgress(sessions, clock)

    override fun search(actorId: String, criteria: BusinessSearchCriteria, context: AdminRequestContext): BusinessQueryResult {
        viewer(actorId) ?: return BusinessQueryResult.Forbidden
        if (!wellFormed(criteria)) return BusinessQueryResult.InvalidInput
        if (!window.admit(actorId)) return rateLimited()
        return found(gateway.search(criteria)).also { log.info("operation=business-search result={}", it.javaClass.simpleName) }
    }

    override fun rejectMalformedSearch(actorId: String): BusinessQueryResult =
        if (viewer(actorId) == null) BusinessQueryResult.Forbidden else BusinessQueryResult.InvalidInput

    override fun detail(actorId: String, businessId: String, context: AdminRequestContext): BusinessQueryResult {
        val view = audit.frontGate(actorId) ?: return BusinessQueryResult.Forbidden
        val target = businessId.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }
        fun audited(resultCode: String, outcome: BusinessQueryResult): BusinessQueryResult =
            if (audit.record(view, context, context.correlationId, VIEWED, resultCode, null, target)) outcome else BusinessQueryResult.AuditUnavailable

        if (view.roles.none { it in BusinessPermissions.VIEW }) return audited("FORBIDDEN", BusinessQueryResult.Forbidden)
        if (target == null) return BusinessQueryResult.InvalidInput
        if (!window.admit(actorId)) {
            metrics.rateLimited(BUSINESS_READ)
            return audited("RATE_LIMITED", BusinessQueryResult.RateLimited)
        }
        return when (val result = gateway.detail(target)) {
            is MasterCallResult.Found -> audited("OK", BusinessQueryResult.Found(withContractProgress(result.document, target, actorId)))
            MasterCallResult.NotFound -> audited("NOT_FOUND", BusinessQueryResult.NotFound)
            is MasterCallResult.Unavailable -> audited("UNAVAILABLE", BusinessQueryResult.Unavailable(result.reason))
        }.also { log.info("operation=business-detail result={}", it.javaClass.simpleName) }
    }

    override fun history(actorId: String, businessId: String, afterSeq: Long?): BusinessQueryResult {
        viewer(actorId) ?: return BusinessQueryResult.Forbidden
        val after = afterSeq ?: 0L
        if (!BusinessAudit.BUSINESS_ID.matches(businessId) || after < 0) return BusinessQueryResult.InvalidInput
        if (!window.admit(actorId)) return rateLimited()
        return when (val result = gateway.history(businessId, after)) {
            is MasterCallResult.Found -> BusinessQueryResult.Found(result.document)
            MasterCallResult.NotFound -> BusinessQueryResult.NotFound
            is MasterCallResult.Unavailable -> BusinessQueryResult.Unavailable(result.reason)
        }.also { log.info("operation=business-history result={}", it.javaClass.simpleName) }
    }

    /**
     * MERCHANT-FLOW-001 FLOW-1(Coordinator 결정 Q1 — D-FLOW-004 ④ 적용): 상세를 찾았을 때만 같은 열람 처리 안에서 MASTER '계약 진행' 섹션을 함께 부른다(DB TX 밖, 사업자 조회 창 추가 소모 없음).
     * 섹션 전용·조회별 감사는 없다 — 감사는 상세 열람 1행(`BUSINESS_VIEWED`, 반환 전)뿐이고 근거 참조 원문 열람도 그 행에 포함된다. 섹션 실패는 섹션만 `available=false`(fail-safe).
     */
    private fun withContractProgress(document: Map<String, Any?>, businessId: String, viewerActorId: String): Map<String, Any?> =
        LinkedHashMap(document).apply {
            // 표시명 조회(ADMIN 계정)·재구성 중 예외도 섹션만 불러오지 못함으로 닫는다 — 예외가 새어 상세 열람 감사 행이 빠지지 않게(로그 = 분류만).
            val raw = runCatching { gateway.contractIntakes(businessId) }.getOrElse { MasterCallResult.Unavailable(it.javaClass.simpleName) }
            val section = try {
                contractProgress.of(raw)
            } catch (e: RuntimeException) {
                log.warn("operation=business-contract-progress result=unavailable failure={}", e.javaClass.simpleName)
                BusinessContractProgress.unavailable()
            }
            put(BusinessContractProgress.KEY, section)
            // BIZ-CRIT-APPROVAL-001 §3.6·§5.1(advisory — 비권위, 권위는 MASTER 잠금 뒤 판정): 계약 이력 = 섹션의 최신 사업자 계약 있음(상태 무관), 가맹점 이력 = merchantCount(종료 포함) > 0.
            // 섹션을 읽지 못하면 null(화면은 판정 보류 — 저장은 MASTER가 판정한다).
            BusinessChangeAdvisory.of(document, raw).let { (mode, subject) ->
                put("criticalChangeMode", mode)
                put("subjectCorrection", subject)
            }
            // §5.2(m-2): 대기 표시는 사업자 조회 권한자(OPERATOR·APPROVER)에게만 — 상태·요청 시각, 내용·요청자 없음.
            put("pendingCriticalChange", runCatching { pendingChanges?.pendingFor(viewerActorId, businessId) }.getOrNull()?.let { linkedMapOf("status" to it.status, "requestedAt" to it.requestedAt) })
        }

    override fun evidence(actorId: String, businessId: String, historyId: Long, context: AdminRequestContext): BusinessQueryResult {
        val view = audit.frontGate(actorId) ?: return BusinessQueryResult.Forbidden
        val target = businessId.takeIf { BusinessAudit.BUSINESS_ID.matches(it) }
        fun audited(resultCode: String, outcome: BusinessQueryResult): BusinessQueryResult =
            if (audit.record(view, context, context.correlationId, EVIDENCE_VIEWED, resultCode, "historyId=$historyId".takeIf { historyId >= 1 }, target)) outcome
            else BusinessQueryResult.AuditUnavailable
        if (view.roles.none { it in BusinessPermissions.VIEW }) return audited("FORBIDDEN", BusinessQueryResult.Forbidden)
        if (target == null || historyId < 1) return BusinessQueryResult.InvalidInput
        if (!window.admit(actorId)) {
            metrics.rateLimited(BUSINESS_READ)
            return audited("RATE_LIMITED", BusinessQueryResult.RateLimited)
        }
        return when (val result = gateway.changeEvidence(target, historyId)) {
            is MasterCallResult.Found -> {
                // APPROVER 또는 그 저장의 요청자(MASTER 저장 행위자 — 재리뷰 nit)만.
                val mayView = APPROVER in view.roles || result.document["recordedBy"] == ACTOR_PREFIX + actorId
                if (mayView) audited("OK", BusinessQueryResult.Found(result.document)) else audited("FORBIDDEN", BusinessQueryResult.Forbidden)
            }
            MasterCallResult.NotFound -> audited("NOT_FOUND", BusinessQueryResult.NotFound)
            is MasterCallResult.Unavailable -> audited("UNAVAILABLE", BusinessQueryResult.Unavailable(result.reason))
        }.also { log.info("operation=business-change-evidence result={}", it.javaClass.simpleName) }
    }

    /** 앞단 + 조회 권한(감사 없음 — 목록·이력). */
    private fun viewer(actorId: String): AdminSessionView? = audit.frontGate(actorId)?.takeIf { v -> v.roles.any { it in BusinessPermissions.VIEW } }

    private fun found(result: MasterCallResult): BusinessQueryResult = when (result) {
        is MasterCallResult.Found -> BusinessQueryResult.Found(result.document)
        MasterCallResult.NotFound -> BusinessQueryResult.Unavailable(UNEXPECTED_NOT_FOUND)
        is MasterCallResult.Unavailable -> BusinessQueryResult.Unavailable(result.reason)
    }

    private fun rateLimited(): BusinessQueryResult {
        metrics.rateLimited(BUSINESS_READ)
        return BusinessQueryResult.RateLimited
    }

    /** MASTER `BusinessSearchRequestParser` 값 규칙 복제(§6.2 — 모든 필드 선택, 상호 접두 = 상호 문자 규칙, KST 날짜 from ≤ to, after `biz_`, limit 1..50). */
    private fun wellFormed(c: BusinessSearchCriteria): Boolean {
        if (c.displayNamePrefix != null && !BusinessProfileInputRules.displayNameWellFormed(c.displayNamePrefix)) return false
        if (c.businessType != null && c.businessType !in BUSINESS_TYPES) return false
        if (c.registrationStatus != null && c.registrationStatus !in REGISTRATION_STATUSES) return false
        if (c.after != null && !BusinessAudit.BUSINESS_ID.matches(c.after)) return false
        if (c.limit != null && c.limit !in 1..MAX_LIMIT) return false
        val from = c.createdFrom?.let { date(it) ?: return false }
        val to = c.createdTo?.let { date(it) ?: return false }
        return from == null || to == null || !from.isAfter(to)
    }

    private fun date(value: String): LocalDate? =
        value.takeIf { DATE_TEXT.matches(it) }?.let { runCatching { LocalDate.parse(it, DATE) }.getOrNull() }

    private companion object {
        val log = LoggerFactory.getLogger(BusinessQueryService::class.java)
        const val VIEWED = "BUSINESS_VIEWED"
        const val EVIDENCE_VIEWED = "BUSINESS_CHANGE_EVIDENCE_VIEWED"
        const val APPROVER = "APPROVER"
        const val ACTOR_PREFIX = "admin:"
        const val BUSINESS_READ = "BUSINESS_READ"
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
        const val MAX_LIMIT = 50
        val BUSINESS_TYPES = setOf("INDIVIDUAL", "CORPORATION")
        val REGISTRATION_STATUSES = setOf("DRAFT", "REGISTERED", "INACTIVE")
        val DATE_TEXT = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT)
    }
}
