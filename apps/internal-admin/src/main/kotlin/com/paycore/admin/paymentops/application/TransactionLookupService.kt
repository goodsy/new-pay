package com.paycore.admin.paymentops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.paymentops.application.provided.TransactionLookupResult
import com.paycore.admin.paymentops.application.provided.TransactionLookupUseCase
import com.paycore.admin.paymentops.application.provided.TransactionSearchCriteria
import com.paycore.admin.paymentops.application.required.PaymentOpsTimings
import com.paycore.admin.paymentops.application.required.PaymentReadGateway
import com.paycore.admin.paymentops.application.required.PaymentReadResult
import com.paycore.admin.paymentops.application.required.TransactionOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * [TransactionLookupUseCase](TXN-OPS-001 §4.1·§5). 판정 순서: 계정 현재 행 ACTIVE + 조회 역할(아니면 Forbidden — 감사 없음) → 형식·모드 규칙(PAYMENT 호출 전, §3.1) → actor별 속도 제한
 * (`lookup-per-minute` — 대상 조회와 같은 값, 별도 창) → PAYMENT 호출(DB TX 밖 — gateway가 TX 안 호출을 거부) → 결과.
 * 상세(D-TXOPS-002): PAYMENT가 답하면(FOUND·NOT_FOUND) `PAYMENT_VIEWED` 감사 1행(targetRef = paymentId)을 **로컬 TX로 먼저 기록하고** 결과를 돌려준다. 감사 실패 → AuditUnavailable(결과 미반환).
 * PAYMENT 불가는 본 것이 없어 감사하지 않는다. 검색(결제 ID 정확 조회 포함)은 감사하지 않는다.
 * 로그에는 연산·결과 분류만(MID·주문번호·결제 ID·주문명 없음).
 */
@Service
class TransactionLookupService(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    private val gateway: PaymentReadGateway,
    private val recorder: AdminAuditRecorder,
    private val timings: PaymentOpsTimings,
    private val metrics: TransactionOpsMetrics,
) : TransactionLookupUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val windows = ConcurrentHashMap<String, LongArray>()

    override fun search(viewerActorId: String, criteria: TransactionSearchCriteria): TransactionLookupResult {
        viewer(viewerActorId) ?: return TransactionLookupResult.Forbidden
        if (!wellFormed(criteria)) return TransactionLookupResult.InvalidInput
        if (!admit(viewerActorId)) return rateLimited()
        return when (val result = gateway.searchPayments(criteria)) {
            is PaymentReadResult.Found -> TransactionLookupResult.Found(result.document)
            PaymentReadResult.NotFound -> TransactionLookupResult.Unavailable(UNEXPECTED_NOT_FOUND)
            is PaymentReadResult.Unavailable -> TransactionLookupResult.Unavailable(result.reason)
        }.also { log.info("operation=transaction-search result={}", it.javaClass.simpleName) }
    }

    override fun rejectMalformedSearch(viewerActorId: String): TransactionLookupResult {
        viewer(viewerActorId) ?: return TransactionLookupResult.Forbidden
        return TransactionLookupResult.InvalidInput.also { log.info("operation=transaction-search result=InvalidInput body=MALFORMED") }
    }

    override fun detail(viewerActorId: String, paymentId: String, context: AdminRequestContext): TransactionLookupResult {
        val viewer = viewer(viewerActorId) ?: return TransactionLookupResult.Forbidden
        if (!PAYMENT_ID.matches(paymentId)) return TransactionLookupResult.InvalidInput
        if (!admit(viewerActorId)) return rateLimited()
        return when (val result = gateway.paymentDetail(paymentId)) {
            is PaymentReadResult.Found -> audited(viewer, context, "OK", paymentId) { TransactionLookupResult.Found(result.document) }
            PaymentReadResult.NotFound -> audited(viewer, context, "NOT_FOUND", paymentId) { TransactionLookupResult.NotFound }
            // PAYMENT가 답하지 않았다 — 본 것이 없어 감사하지 않는다(§5.1).
            is PaymentReadResult.Unavailable -> TransactionLookupResult.Unavailable(result.reason)
        }.also { log.info("operation=transaction-detail result={}", it.javaClass.simpleName) }
    }

    private fun viewer(actorId: String): AdminSessionView? =
        sessions.current(actorId)?.takeIf { it.isActive && it.roles.any { role -> role in VIEW_ROLES } }

    /** §3.1 형식·모드 규칙(PAYMENT 파서와 같은 규칙 — PAYMENT도 다시 검사한다). */
    private fun wellFormed(c: TransactionSearchCriteria): Boolean {
        val textsOk = (c.environment == null || c.environment in ENVIRONMENTS) && (c.merchantAccountId == null || REF.matches(c.merchantAccountId)) &&
            (c.merchantOrderId == null || REF.matches(c.merchantOrderId)) && (c.paymentId == null || PAYMENT_ID.matches(c.paymentId)) &&
            (c.status == null || c.status in STATUSES) && (c.limit == null || c.limit in 1..MAX_LIMIT)
        if (!textsOk) return false
        if ((c.afterCreatedAt == null) != (c.afterPaymentId == null)) return false
        if (c.afterCreatedAt != null && (instant(c.afterCreatedAt) == null || !PAYMENT_ID.matches(c.afterPaymentId!!))) return false
        if ((c.createdFrom == null) != (c.createdTo == null)) return false
        if (c.createdFrom != null) {
            val from = instant(c.createdFrom) ?: return false
            val to = instant(c.createdTo!!) ?: return false
            if (from >= to || to.isAfter(from.plus(MAX_PERIOD))) return false
        }
        return when {
            c.paymentId != null -> c.createdFrom == null && c.environment == null && c.merchantAccountId == null && c.merchantOrderId == null && c.status == null &&
                c.afterCreatedAt == null
            c.merchantOrderId != null -> c.merchantAccountId != null
            else -> c.createdFrom != null
        }
    }

    private fun instant(value: String): Instant? = if (!TIME.matches(value)) null else try {
        Instant.parse(value)
    } catch (e: DateTimeParseException) {
        null
    }

    private fun rateLimited(): TransactionLookupResult {
        metrics.rateLimited()
        return TransactionLookupResult.RateLimited
    }

    private fun audited(
        viewer: AdminSessionView,
        context: AdminRequestContext,
        resultCode: String,
        paymentId: String,
        outcome: () -> TransactionLookupResult,
    ): TransactionLookupResult = try {
        transactions.execute({ false }) {
            recorder.record(
                AuditRecord(
                    occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = viewer.actorId, actorDisplayName = viewer.displayName,
                    action = VIEWED, resultCode = resultCode, correlationId = context.correlationId, remoteIp = context.remoteIp, targetRef = paymentId,
                ),
            )
        }
        // 감사가 커밋된 뒤에만 결과를 만든다(D-TXOPS-002 — 감사 실패 = 행위 실패).
        outcome()
    } catch (e: RuntimeException) {
        log.warn("operation=transaction-audit action={} result=audit-unavailable failure={}", VIEWED, e.javaClass.simpleName)
        TransactionLookupResult.AuditUnavailable
    }

    /** 고정 창(분) 계수: [0] = 창 번호, [1] = 그 창의 호출 수(인스턴스 메모리 — 다중 인스턴스 합산은 LIVE Gate). */
    private fun admit(actorId: String): Boolean {
        val minute = System.currentTimeMillis() / MINUTE_MS
        val slot = windows.computeIfAbsent(actorId) { LongArray(2) }
        synchronized(slot) {
            if (slot[0] != minute) {
                slot[0] = minute
                slot[1] = 0
            }
            if (slot[1] >= timings.lookupPerMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(TransactionLookupService::class.java)

        /** 조회 역할(I-1 Implementer 해석 — 기존 결제 대상 조회·가맹점 조회와 같은 VIEW_MASKED 4역할). */
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        val PAYMENT_ID = Regex("^pay_[0-9A-HJKMNP-TV-Z]{26}$")
        val REF = Regex("^[A-Za-z0-9._:-]{1,64}$")
        val TIME = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$")
        val ENVIRONMENTS = setOf("TEST", "LIVE")
        val STATUSES = setOf("REQUESTED", "AUTHORIZING", "UNKNOWN", "AUTHORIZED", "FAILED", "EXPIRED")
        const val MAX_LIMIT = 50

        /** 기간 상한(D-TXOPS-001 — Demo 값, 운영값 아님 — PAYMENT와 같은 값). */
        val MAX_PERIOD: Duration = Duration.ofDays(31)
        const val MINUTE_MS = 60_000L
        const val VIEWED = "PAYMENT_VIEWED"
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
    }
}
