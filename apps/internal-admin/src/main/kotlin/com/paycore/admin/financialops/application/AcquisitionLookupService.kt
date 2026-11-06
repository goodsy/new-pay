package com.paycore.admin.financialops.application

import com.paycore.admin.account.application.provided.AdminRequestContext
import com.paycore.admin.account.application.provided.AdminSessionQuery
import com.paycore.admin.account.application.provided.AdminSessionView
import com.paycore.admin.audit.application.provided.AdminAuditRecorder
import com.paycore.admin.audit.application.provided.AuditRecord
import com.paycore.admin.financialops.application.provided.AcquisitionFileSearchCriteria
import com.paycore.admin.financialops.application.provided.AcquisitionLookupResult
import com.paycore.admin.financialops.application.provided.AcquisitionLookupUseCase
import com.paycore.admin.financialops.application.provided.AcquisitionRecordSearchCriteria
import com.paycore.admin.financialops.application.required.FinancialCallResult
import com.paycore.admin.financialops.application.required.FinancialOpsGateway
import com.paycore.admin.financialops.application.required.FinancialOpsLimits
import com.paycore.admin.financialops.application.required.FinancialOpsMetrics
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * [AcquisitionLookupUseCase](ACQ-VIEW-001 §4.6). 판정 순서: 계정 현재 행 ACTIVE + 조회 역할(VIEW_MASKED 4역할 — D-ACQV-001, 아니면 Forbidden·감사 없음) → 형식·모드·기간 검사
 * (FINANCIAL 호출 전 — FINANCIAL 파서와 같은 규칙) → actor별 속도 제한 → FINANCIAL 호출(DB TX 밖 — gateway가 TX 안 호출을 거부) → 결과. 해석되지 않은 본문도 이 순서다
 * ([rejectMalformedSearch] — 역할이 먼저, B2 리뷰 MINOR-2).
 * 파일 상세·레코드 쪽은 FINANCIAL이 답하면(FOUND·NOT_FOUND) `ACQUISITION_FILE_VIEWED` 감사 1행(targetRef = fileId, detail 없음)을 로컬 TX로 쓴 **뒤에만** 결과를 돌려준다 — 감사 실패는
 * AuditUnavailable(결과 미반환). FINANCIAL 불가는 본 것이 없어 감사하지 않는다. 목록은 감사하지 않는다. 로그에는 연산·결과 분류만(fileId·입력 값 없음).
 */
@Service
class AcquisitionLookupService(
    transactionManager: PlatformTransactionManager,
    private val sessions: AdminSessionQuery,
    private val gateway: FinancialOpsGateway,
    private val recorder: AdminAuditRecorder,
    private val limits: FinancialOpsLimits,
    private val metrics: FinancialOpsMetrics,
) : AcquisitionLookupUseCase {

    private val transactions = LocalTransactions(transactionManager, maxAttempts = 1)
    private val windows = ConcurrentHashMap<String, LongArray>()

    override fun search(viewerActorId: String, criteria: AcquisitionFileSearchCriteria): AcquisitionLookupResult {
        viewer(viewerActorId) ?: return AcquisitionLookupResult.Forbidden
        if (!wellFormed(criteria)) return AcquisitionLookupResult.InvalidInput
        if (!admit(viewerActorId)) return rateLimited()
        return when (val result = gateway.search(criteria)) {
            is FinancialCallResult.Found -> AcquisitionLookupResult.Found(result.document)
            FinancialCallResult.NotFound -> AcquisitionLookupResult.Unavailable(UNEXPECTED_NOT_FOUND)
            is FinancialCallResult.Unavailable -> AcquisitionLookupResult.Unavailable(result.reason)
        }.also { log.info("operation=acquisition-search result={}", it.javaClass.simpleName) }
    }

    override fun rejectMalformedSearch(viewerActorId: String): AcquisitionLookupResult {
        viewer(viewerActorId) ?: return AcquisitionLookupResult.Forbidden
        return AcquisitionLookupResult.InvalidInput.also { log.info("operation=acquisition-search result=InvalidInput body=MALFORMED") }
    }

    override fun detail(viewerActorId: String, fileId: String, context: AdminRequestContext): AcquisitionLookupResult {
        val viewer = viewer(viewerActorId) ?: return AcquisitionLookupResult.Forbidden
        if (!FILE_ID.matches(fileId)) return AcquisitionLookupResult.InvalidInput
        if (!admit(viewerActorId)) return rateLimited()
        return seen(viewer, context, fileId, gateway.detail(fileId)).also { log.info("operation=acquisition-detail result={}", it.javaClass.simpleName) }
    }

    override fun records(viewerActorId: String, fileId: String, criteria: AcquisitionRecordSearchCriteria, context: AdminRequestContext): AcquisitionLookupResult {
        val viewer = viewer(viewerActorId) ?: return AcquisitionLookupResult.Forbidden
        if (!FILE_ID.matches(fileId) || !wellFormed(criteria)) return AcquisitionLookupResult.InvalidInput
        if (!admit(viewerActorId)) return rateLimited()
        return seen(viewer, context, fileId, gateway.records(fileId, criteria)).also { log.info("operation=acquisition-records result={}", it.javaClass.simpleName) }
    }

    /** FINANCIAL이 답했으면(FOUND·NOT_FOUND) 감사 후 결과, 답하지 않았으면 감사 없이 Unavailable. */
    private fun seen(viewer: AdminSessionView, context: AdminRequestContext, fileId: String, result: FinancialCallResult): AcquisitionLookupResult = when (result) {
        is FinancialCallResult.Found -> audited(viewer, context, "OK", fileId) { AcquisitionLookupResult.Found(result.document) }
        FinancialCallResult.NotFound -> audited(viewer, context, "NOT_FOUND", fileId) { AcquisitionLookupResult.NotFound }
        is FinancialCallResult.Unavailable -> AcquisitionLookupResult.Unavailable(result.reason)
    }

    private fun viewer(actorId: String): AdminSessionView? =
        sessions.current(actorId)?.takeIf { it.isActive && it.roles.any { role -> role in VIEW_ROLES } }

    private fun wellFormed(c: AcquisitionFileSearchCriteria): Boolean {
        val from = c.businessDateFrom?.let(::date)
        val to = c.businessDateTo?.let(::date)
        if ((c.businessDateFrom != null && from == null) || (c.businessDateTo != null && to == null)) return false
        val formats = (c.acquirerCode == null || ACQUIRER_CODE.matches(c.acquirerCode)) && (c.environment == null || c.environment in ENVIRONMENTS) &&
            (c.transmissionState == null || c.transmissionState in TRANSMISSION) && (c.receiptState == null || c.receiptState in RECEIPT) &&
            (c.resultState == null || c.resultState in RESULT) && (c.needsAttention == null || c.needsAttention) &&
            (c.limit == null || c.limit in 1..MAX_LIMIT) && (c.after == null || (date(c.after.businessDate) != null && FILE_ID.matches(c.after.fileId)))
        if (!formats) return false
        if (c.fileId != null) {
            // 정확 조회: 다른 조건 없음(FINANCIAL §3.3 모드 규칙).
            return FILE_ID.matches(c.fileId) && from == null && to == null && c.acquirerCode == null && c.environment == null && c.transmissionState == null &&
                c.receiptState == null && c.resultState == null && c.needsAttention == null && c.after == null
        }
        return from != null && to != null && !to.isBefore(from) && !to.isAfter(from.plusDays(MAX_PERIOD_DAYS - 1))
    }

    private fun wellFormed(c: AcquisitionRecordSearchCriteria): Boolean =
        (c.status == null || c.status in RECORD_STATUSES) && (c.afterSeq == null || c.afterSeq >= 0) && (c.limit == null || c.limit in 1..MAX_LIMIT)

    /** 엄격 ISO 달력일(`yyyy-MM-dd`, 2월 30일 거부). */
    private fun date(value: String): LocalDate? = if (DATE.matches(value)) {
        try {
            LocalDate.parse(value)
        } catch (e: DateTimeParseException) {
            null
        }
    } else {
        null
    }

    private fun rateLimited(): AcquisitionLookupResult {
        metrics.rateLimited()
        return AcquisitionLookupResult.RateLimited
    }

    private fun audited(viewer: AdminSessionView, context: AdminRequestContext, resultCode: String, fileId: String, outcome: () -> AcquisitionLookupResult): AcquisitionLookupResult =
        try {
            transactions.execute({ false }) {
                recorder.record(
                    AuditRecord(
                        occurredAt = Instant.now().truncatedTo(ChronoUnit.MILLIS), actorKind = "ADMIN", actorId = viewer.actorId, actorDisplayName = viewer.displayName,
                        action = VIEWED, resultCode = resultCode, correlationId = context.correlationId, remoteIp = context.remoteIp, detail = null, targetRef = fileId,
                    ),
                )
            }
            outcome()
        } catch (e: RuntimeException) {
            log.warn("operation=acquisition-audit result=audit-unavailable failure={}", e.javaClass.simpleName)
            AcquisitionLookupResult.AuditUnavailable
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
            if (slot[1] >= limits.lookupPerMinute) return false
            slot[1]++
            return true
        }
    }

    private companion object {
        val log = LoggerFactory.getLogger(AcquisitionLookupService::class.java)

        /** D-ACQV-001: 거래 조회와 같은 VIEW_MASKED 4역할(UNMASK 단독 제외). */
        val VIEW_ROLES = setOf("VIEWER", "OPERATOR", "APPROVER", "SECURITY_ADMIN")
        val FILE_ID = Regex("^ACQ\\.[A-Z0-9]{1,32}\\.(TEST|LIVE)\\.[0-9]{8}\\.[0-9]{3}$")
        val DATE = Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$")
        val ACQUIRER_CODE = Regex("^[A-Z0-9]{1,32}$")
        val ENVIRONMENTS = setOf("TEST", "LIVE")
        val TRANSMISSION = setOf("SEALED", "TRANSMITTING", "TRANSMITTED", "TRANSMISSION_UNKNOWN", "TRANSMISSION_QUARANTINED")
        val RECEIPT = setOf("NONE", "RECEIVED", "FILE_REJECTED", "RECEIPT_CONFLICT")
        val RESULT = setOf("AWAITING", "COMPLETE", "DEADLINE_PASSED")
        val RECORD_STATUSES = setOf("AWAITING", "ACCEPTED", "REJECTED", "FILE_REJECTED", "UNKNOWN", "CONFLICT")
        const val MAX_LIMIT = 50

        /** 기간 상한(I-3 — Demo 값, FINANCIAL과 같은 값). 양끝 포함 일수. */
        const val MAX_PERIOD_DAYS = 31L
        const val MINUTE_MS = 60_000L
        const val VIEWED = "ACQUISITION_FILE_VIEWED"
        const val UNEXPECTED_NOT_FOUND = "UNEXPECTED_NOT_FOUND"
    }
}
