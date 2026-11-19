package com.paycore.admin.merchantops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.merchantops.application.provided.MerchantLookupResult
import com.paycore.admin.merchantops.application.provided.MerchantLookupUseCase
import com.paycore.admin.merchantops.application.provided.MerchantSearchCriteria
import com.paycore.admin.merchantops.application.required.MasterCallResult
import com.paycore.admin.merchantops.application.required.MasterOpsGateway
import com.paycore.admin.merchantops.application.required.MerchantOpsLimits
import com.paycore.admin.merchantops.application.required.MerchantOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * [MerchantLookupUseCase](MERCHANT-OPS-001 §4.1·§5). 판정 순서와 감사:
 * - 공통: 계정 현재 행 ACTIVE + 조회 역할(아니면 [MerchantLookupResult.Forbidden], 감사 없음 — 다른 조회와 같다) → 형식 검사(MASTER 호출 전) → actor별 속도 제한 → MASTER 호출
 *   (DB TX 밖 — gateway가 TX 안 호출을 거부) → 결과.
 * - 사업자등록번호 검색(§5.2): 조회 역할 뒤 **등록번호 역할(OPERATOR·APPROVER — §5.3)** → 형식(ASCII 숫자 10자리) → 등록번호 전용 속도 제한 → 일반 속도 제한 → MASTER. **모든 시도**를
 *   `MERCHANT_BRN_SEARCHED`로 감사한다(결과 FORBIDDEN·INVALID_INPUT·RATE_LIMITED·OK·UNAVAILABLE, detail은 마스킹 값 `brn=123*******`(형식 위반은 `brn=INVALID`)과 결과 수, 찾았으면
 *   targetRef = businessId — 평문 없음 §5.4).
 * - 엄격 해석에 실패한 검색 본문(Q-MOPS-07): adapter가 판정한 등록번호 검색 시도면 같은 순서(등록번호 역할 → 형식)로 `FORBIDDEN`·`INVALID_INPUT` 감사(detail 고정 `brn=INVALID`),
 *   아니면 감사 없는 InvalidInput. 본문 원문은 받지 않는다.
 * - 상세(§5.1): MASTER가 답하면(FOUND·NOT_FOUND) `MERCHANT_VIEWED` 감사 — 대상(MERCHANT-CODE-001 §5.6.4 C-32): FOUND는 표기 targetRef = 응답 merchantCode(현재 코드),
 *   NOT_FOUND는 코드가 없으므로 mer_; 내부 키 targetMerchantId = merchantId(mer_)는 둘 다. 목록(등록번호 없음)·이력은 감사하지 않는다(D-MOPS-002).
 * - 감사 쓰기 실패 → [MerchantLookupResult.AuditUnavailable]이고 **결과를 돌려주지 않는다**(감사 실패 = 행위 실패).
 * - 가맹점명 앞부분(MERCHANT-NAME-SEARCH-001 MNS-2): MASTER `DisplayName` 규칙([RegistrationInputRules.displayName] — 1~100 code point, 앞뒤 공백·제어문자·짝 없는
 *   서로게이트 금지, 정규화 없음)으로 MASTER 호출 전에 검사한다. 목록 검색이라 감사 없음·일반 속도 창, 등록번호와 함께면 등록번호 경로(이름 위반도 `INVALID_INPUT` 감사 — 이름은 감사에 없다),
 *   코드 검색과 함께면 400.
 * 로그에는 연산·결과 분류만(등록번호·가맹점명·입력 값 없음).
 */
@Service
class MerchantLookupService(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    private val gateway: MasterOpsGateway,
    private val recorder: AdminAuditRecorder,
    private val limits: MerchantOpsLimits,
    private val metrics: MerchantOpsMetrics,
    private val registrationNumbers: RegistrationNumberBudget,
) : MerchantLookupUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val lookupWindows = ConcurrentHashMap<String, LongArray>()

    override fun search(viewerActorId: String, criteria: MerchantSearchCriteria, context: AdminRequestContext): MerchantLookupResult {
        val viewer = viewer(viewerActorId) ?: return MerchantLookupResult.Forbidden
        if (criteria.registrationNumber != null) return searchByRegistrationNumber(viewer, criteria, context)
        if (!wellFormed(criteria)) return MerchantLookupResult.InvalidInput
        if (!admit(lookupWindows, viewerActorId, limits.lookupPerMinute)) return rateLimited(LOOKUP)
        return when (val result = gateway.search(criteria)) {
            is MasterCallResult.Found -> MerchantLookupResult.Found(result.document)
            MasterCallResult.NotFound -> MerchantLookupResult.Unavailable(UNEXPECTED_NOT_FOUND)
            is MasterCallResult.Unavailable -> MerchantLookupResult.Unavailable(result.reason)
        }.also { log.info("operation=merchant-search result={}", it.javaClass.simpleName) }
    }

    override fun rejectMalformedSearch(viewerActorId: String, registrationNumberAttempt: Boolean, context: AdminRequestContext): MerchantLookupResult {
        val viewer = viewer(viewerActorId) ?: return MerchantLookupResult.Forbidden
        if (!registrationNumberAttempt) return MerchantLookupResult.InvalidInput.also { log.info("operation=merchant-search result=InvalidInput") }
        // §5.2 순서(Q-MOPS-07): 등록번호 역할 → 형식. 본문에서 아무것도 꺼내지 않는다 — detail은 고정 값 brn=INVALID.
        val resultCode = if (viewer.roles.none { it in REGISTRATION_NUMBER_ROLES }) "FORBIDDEN" else "INVALID_INPUT"
        val outcome = if (resultCode == "FORBIDDEN") MerchantLookupResult.Forbidden else MerchantLookupResult.InvalidInput
        return audited(viewer, context, BRN_SEARCHED, resultCode, INVALID_NUMBER, null, null) { outcome }.also {
            log.info("operation=merchant-brn-search result={} audit={} body=MALFORMED", it.javaClass.simpleName, resultCode)
        }
    }

    private fun searchByRegistrationNumber(viewer: AdminSessionView, criteria: MerchantSearchCriteria, context: AdminRequestContext): MerchantLookupResult {
        val number = criteria.registrationNumber!!
        val numberWellFormed = REGISTRATION_NUMBER.matches(number)
        val masked = if (numberWellFormed) "brn=" + number.take(VISIBLE_DIGITS) + "*".repeat(MASKED_DIGITS) else INVALID_NUMBER
        fun audit(resultCode: String, detail: String = masked, targetRef: String? = null, outcome: () -> MerchantLookupResult) =
            audited(viewer, context, BRN_SEARCHED, resultCode, detail, targetRef, null, outcome).also {
                log.info("operation=merchant-brn-search result={} audit={}", it.javaClass.simpleName, resultCode)
            }

        if (viewer.roles.none { it in REGISTRATION_NUMBER_ROLES }) return audit("FORBIDDEN") { MerchantLookupResult.Forbidden }
        if (!numberWellFormed || !wellFormed(criteria)) return audit("INVALID_INPUT") { MerchantLookupResult.InvalidInput }
        // MERCHANT-OPS-002 C-13: 등록번호 버킷은 사업자 등록(중복 결과 확정)과 공유한다([RegistrationNumberBudget]).
        if (!registrationNumbers.admit(viewer.actorId)) {
            metrics.rateLimited(REGISTRATION_NUMBER_KIND)
            return audit("RATE_LIMITED") { MerchantLookupResult.RateLimited }
        }
        if (!admit(lookupWindows, viewer.actorId, limits.lookupPerMinute)) {
            metrics.rateLimited(LOOKUP)
            return audit("RATE_LIMITED") { MerchantLookupResult.RateLimited }
        }
        return when (val result = gateway.search(criteria)) {
            is MasterCallResult.Found -> {
                @Suppress("UNCHECKED_CAST")
                val items = result.document["items"] as List<Map<String, Any?>>
                // 등록번호는 Business에 UNIQUE — 결과는 한 사업자뿐이다. targetRef로 정확히 어느 사업자를 조회했는지 남긴다(§5.4).
                val businessId = items.map { it["businessId"] as String }.distinct().singleOrNull()
                audit("OK", "$masked;results=${items.size}", businessId) { MerchantLookupResult.Found(result.document) }
            }
            MasterCallResult.NotFound -> audit("UNAVAILABLE") { MerchantLookupResult.Unavailable(UNEXPECTED_NOT_FOUND) }
            is MasterCallResult.Unavailable -> audit("UNAVAILABLE") { MerchantLookupResult.Unavailable(result.reason) }
        }
    }

    override fun detail(viewerActorId: String, merchantId: String, context: AdminRequestContext): MerchantLookupResult {
        val viewer = viewer(viewerActorId) ?: return MerchantLookupResult.Forbidden
        if (!MERCHANT_ID.matches(merchantId)) return MerchantLookupResult.InvalidInput
        if (!admit(lookupWindows, viewerActorId, limits.lookupPerMinute)) return rateLimited(LOOKUP)
        return when (val result = gateway.detail(merchantId)) {
            // MERCHANT-CODE-001 MC-3a(§5.6.4 C-32): FOUND = 응답의 현재 코드 표기 + 내부 키, NOT_FOUND = 코드가 없으므로 mer_ + mer_.
            is MasterCallResult.Found ->
                audited(viewer, context, VIEWED, "OK", null, codeOf(result.document) ?: merchantId, merchantId) { MerchantLookupResult.Found(result.document) }
            MasterCallResult.NotFound -> audited(viewer, context, VIEWED, "NOT_FOUND", null, merchantId, merchantId) { MerchantLookupResult.NotFound }
            // MASTER가 답하지 않았다 — 본 것이 없어 감사하지 않는다(§5.1).
            is MasterCallResult.Unavailable -> MerchantLookupResult.Unavailable(result.reason)
        }.also { log.info("operation=merchant-detail result={}", it.javaClass.simpleName) }
    }

    override fun history(viewerActorId: String, merchantId: String, afterSeq: Long?): MerchantLookupResult {
        viewer(viewerActorId) ?: return MerchantLookupResult.Forbidden
        val after = afterSeq ?: 0L
        if (!MERCHANT_ID.matches(merchantId) || after < 0) return MerchantLookupResult.InvalidInput
        if (!admit(lookupWindows, viewerActorId, limits.lookupPerMinute)) return rateLimited(LOOKUP)
        return when (val result = gateway.history(merchantId, after)) {
            is MasterCallResult.Found -> MerchantLookupResult.Found(result.document)
            MasterCallResult.NotFound -> MerchantLookupResult.NotFound
            is MasterCallResult.Unavailable -> MerchantLookupResult.Unavailable(result.reason)
        }
    }

    private fun viewer(actorId: String): AdminSessionView? =
        sessions.current(actorId)?.takeIf { it.isActive && it.roles.any { role -> role in VIEW_ROLES } }

    private fun wellFormed(c: MerchantSearchCriteria): Boolean =
        (c.merchantId == null || MERCHANT_ID.matches(c.merchantId)) && (c.businessId == null || BUSINESS_ID.matches(c.businessId)) &&
            (c.lifecycle == null || c.lifecycle in LIFECYCLES) && (c.after == null || MERCHANT_ID.matches(c.after)) &&
            (c.limit == null || c.limit in 1..MAX_LIMIT) && (c.registrationNumber == null || REGISTRATION_NUMBER.matches(c.registrationNumber)) &&
            (c.merchantCode == null || codeSearchWellFormed(c)) && (c.displayNamePrefix == null || RegistrationInputRules.displayName(c.displayNamePrefix))

    /**
     * MERCHANT-CODE-001 MC-2(§3.8): 코드 검색은 §3.1 형식(정규화 없음)이고 **단일 기준** — MASTER `MerchantSearchRequestParser`와 같이 `limit`만 함께 올 수 있다
     * (다른 필터·cursor·등록번호와 함께면 MASTER 호출 전 400 — 은퇴 코드 일치 의미를 다른 조건과 섞지 않는다).
     * MERCHANT-NAME-SEARCH-001 MNS-2(§4): 가맹점명 조건도 함께 올 수 없다(MASTER `MerchantSearchFilter` 불변식과 같다).
     */
    private fun codeSearchWellFormed(c: MerchantSearchCriteria): Boolean =
        MERCHANT_CODE.matches(c.merchantCode!!) && c.merchantId == null && c.businessId == null && c.lifecycle == null && c.after == null && c.registrationNumber == null &&
            c.displayNamePrefix == null

    private fun rateLimited(kind: String): MerchantLookupResult {
        metrics.rateLimited(kind)
        return MerchantLookupResult.RateLimited
    }

    /**
     * MC-3a(C-32): 상세 응답(모양 검사 통과 — `merchant.merchantCode` 필수)의 현재 코드. §3.1 형식이 아니면 null(호출자는 mer_ 표기 — 감사를 잃지 않는다, 가역 결정).
     */
    @Suppress("UNCHECKED_CAST")
    private fun codeOf(document: Map<String, Any?>): String? =
        ((document["merchant"] as? Map<String, Any?>)?.get("merchantCode") as? String)?.takeIf { MERCHANT_CODE.matches(it) }

    @Suppress("LongParameterList")
    private fun audited(
        viewer: AdminSessionView,
        context: AdminRequestContext,
        action: String,
        resultCode: String,
        detail: String?,
        targetRef: String?,
        targetMerchantId: String?,
        outcome: () -> MerchantLookupResult,
    ): MerchantLookupResult = try {
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = viewer.actorId, actorDisplayName = viewer.displayName,
                    action = action, resultCode = resultCode, correlationId = context.correlationId, remoteIp = context.remoteIp, detail = detail, targetRef = targetRef,
                    // MERCHANT-CODE-001 MC-3a(C-32): 가맹점 관련 행은 내부 키 필수(사업자 등록번호 조회 행은 null — biz_ 대상).
                    targetMerchantId = targetMerchantId,
                ),
            )
        }
        outcome()
    } catch (e: RuntimeException) {
        log.warn("operation=merchant-ops-audit action={} result=audit-unavailable failure={}", action, e.javaClass.simpleName)
        MerchantLookupResult.AuditUnavailable
    }

    /** 고정 창(분) 계수: [0] = 창 번호, [1] = 그 창의 호출 수(인스턴스 메모리 — 다중 인스턴스 합산은 LIVE Gate). */
    private fun admit(windows: ConcurrentHashMap<String, LongArray>, actorId: String, perMinute: Int): Boolean {
        val minute = System.currentTimeMillis() / MINUTE_MS
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= perMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(MerchantLookupService::class.java)
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")

        /** 전체 사업자등록번호 검색 역할(§5.3 — 사용자 직접 답변 Q-MOPS-05, 2026-11-04 (D-MOPS-003)): 업무 운영 역할만. VIEWER·SECURITY_ADMIN·UNMASK 단독 제외. */
        val REGISTRATION_NUMBER_ROLES = setOf("OPERATOR", "APPROVER")
        private const val ULID = "[0-9A-HJKMNP-TV-Z]{26}"
        val MERCHANT_ID = Regex("^mer_$ULID$")
        val BUSINESS_ID = Regex("^biz_$ULID$")
        val REGISTRATION_NUMBER = Regex("^[0-9]{10}$")

        /** MERCHANT-CODE-001 §3.1(등록 입력 규칙과 같은 정규식 — 한 곳). */
        val MERCHANT_CODE = RegistrationInputRules.MERCHANT_CODE
        val LIFECYCLES = setOf("ACTIVE", "TERMINATED")
        const val MAX_LIMIT = 50
        const val VISIBLE_DIGITS = 3
        const val MASKED_DIGITS = 7
        const val MINUTE_MS = 60_000L
        const val VIEWED = "MERCHANT_VIEWED"
        const val BRN_SEARCHED = "MERCHANT_BRN_SEARCHED"
        const val INVALID_NUMBER = "brn=INVALID"
        const val LOOKUP = "LOOKUP"
        const val REGISTRATION_NUMBER_KIND = "REGISTRATION_NUMBER"
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
    }
}
