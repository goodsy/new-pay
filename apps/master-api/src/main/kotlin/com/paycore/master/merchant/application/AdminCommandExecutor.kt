package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.provided.ApprovalDecidedVia
import com.paycore.master.merchant.application.provided.ApprovalOutcome
import com.paycore.master.merchant.application.provided.DeadlineConfirmationResult
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.ApprovalEvidenceRecord
import com.paycore.master.merchant.application.required.ApprovalEvidenceStore
import com.paycore.master.merchant.application.required.ChangeRecord
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.MasterOperationalMetrics
import com.paycore.master.merchant.application.required.OutboxEvent
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.application.required.StoredAdminResult
import com.paycore.master.merchant.domain.ApprovalEvidence
import com.paycore.master.merchant.domain.ApprovalWindow
import com.paycore.master.merchant.domain.MasterRejection
import com.paycore.master.merchant.domain.MasterRuleViolation
import com.paycore.master.merchant.domain.Merchant
import com.paycore.master.merchant.application.required.MasterClock
import org.slf4j.LoggerFactory
import org.springframework.transaction.CannotCreateTransactionException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.UnexpectedRollbackException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.HexFormat

/**
 * 관리 명령 공통 실행기(MASTER 로컬 TX, 명세 §3.1–§3.3). 한 TX 안에서:
 *
 * 1. 멱등 기록 예약/잠금(insert-first, 범위 (operation, key)). 이미 있으면 fingerprint가 같을 때 저장 결과 재생, 다르면 IdempotencyKeyReused.
 * 2. 업무 본문([Work]): 잠금 → domain 판정 → **판정이 끝난 뒤** [Work.beginWrites] → 저장·이력·Outbox. 판정 단계의 [MasterRuleViolation]은
 *    업무 거절로 저장해 같은 키 재요청에 재생한다. 쓰기 시작 뒤의 규칙 위반은 결함으로 보고 TX 전체를 rollback한다(일부 쓰기 + 거절이
 *    함께 커밋되는 일이 없게).
 * 3. 결과 기록 후 commit. 응답은 commit 뒤.
 *
 * 재시도(새 TX로 처음부터 같은 의도): DB가 rollback을 확정한 교착, 낙관적 version 충돌, **업무 유일키 충돌**(동시 요청 경합 — 다음 TX의 판정이
 * 커밋된 행을 보고 업무 거절로 수렴). 최대 [MAX_ATTEMPTS]회(기술 설정). 서버 생성 ID 충돌은 [AdminCommandFailure.IdentifierCollision].
 *
 * 시각: 명령 시각은 서버 [MasterClock]에서 오고 저장 정밀도(ms)로 자른다(review m-3·m-4). 호출자 시각은 받지 않는다(fingerprint에도 시각 없음).
 * **판정·저장 시각은 직렬화 잠금을 잡은 뒤에 읽는다**(review n-1): 본문이 business/merchant/contract 등을 잠근 다음 [Work.lockedNow]를 한 번
 * 부르고, 그 값을 판정(적용 Revision D-MD-011·015, 종료 Revision 승인 거절 D-MD-014, 소급 금지 D-MD-005)과 저장 시각에 일관되게 쓴다.
 * 잠금 대기 동안 흐른 시간이 오래된 시각으로 판정되지 않게. 멱등 기록 예약 시각은 요청 도착 시각(잠금 전)이다.
 *
 * 운영 로그(review m-8, 구조화 key=value, 민감값 없음 — 등록번호·채널 식별값·멱등키 값·입력값을 넣지 않는다): 결과 불명확(OutcomeUnknown),
 * 재시도 소진, 서버 생성 ID 충돌을 WARN으로 남긴다. 필드: operation, correlationId, requestRecordId(있으면), failure 종류.
 * 같은 사건을 [MasterOperationalMetrics] counter로도 센다(review m-7·m-8, MASTER-SNAPSHOT-001 — label은 연산 코드만).
 *
 * 권리 Snapshot(MASTER-SNAPSHOT-001 §4.6): 권리 입력을 바꾼 본문은 쓰기 단계에서 [Work.publishEntitlement]로 같은 TX에 Snapshot 발행
 * (issue + Outbox)을 남긴다. 조립은 같은 TX의 JPA 읽기다(변경과 Snapshot이 원자적 — 두 번째 hop 없음).
 *
 * **승인 근거 명령**(MERCHANT-OPS-002 §9.3·§9.4 — [executeApproved]·[confirmDeadline], operation 매개변수형: B2-2 계약 승인, B2-3 첫 MID가 같은 틀). 기존 [execute](B1·seed 경로)는
 * 바뀌지 않는다. 한 로컬 TX 안의 순서: 멱등 예약(재생/409는 [execute]와 같음) → 판정 1·2 [ApprovalEvidence.admit](예약 시각) → 판정 3 approvalId 근거 행 존재(잠금 없는 조회)
 * → 업무 본문. 본문의 [Work.lockedNow]가 시각을 읽은 **직후** 판정 4(만료) 훅이 돈다(본문이 빠뜨릴 수 없게 — m-4). 근거 모드에서 [Work.lockedNow] 없이 [Work.beginWrites]는 결함
 * (IllegalState → rollback). 성공은 쓰기 단계에서 EXECUTED 근거 행, 저장되는 거절(판정 3 제외)은 같은 TX에 REJECTED 근거 행을 쓴 뒤 `admin_request` 결과를 완료한다(불변식 n-a).
 * 근거 행 PK 충돌은 Store가 flush 시점에 `StoreConstraint.APPROVAL_EVIDENCE`(업무 유일키)로 번역 → 새 TX 재판정에서 판정 3으로 수렴한다(m-1).
 */
internal class AdminCommandExecutor(
    transactionManager: PlatformTransactionManager,
    private val requests: AdminRequestStore,
    private val changeLog: MasterChangeLog,
    private val outbox: OutboxStore,
    private val ids: MasterIdGenerator,
    private val clock: MasterClock,
    private val metrics: MasterOperationalMetrics,
    private val publisher: EntitlementSnapshotPublisher,
    private val evidences: ApprovalEvidenceStore? = null,
) {
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(AdminCommandExecutor::class.java)

    fun execute(operation: AdminOperation, request: AdminRequest, fingerprintFields: List<String?>, body: (Work) -> String): AdminCommandResult {
        val fingerprint = fingerprintOf(operation, fingerprintFields)
        return classified(operation, request) { transactions.execute(::isRetryable) { once(operation, request, fingerprint, body) } }
    }

    /**
     * 승인 근거 명령 실행(§9.3). [fingerprintFields]에는 명령 필드와 근거 필드 전부(approvalId·요청자·승인자·시각 2개·근거 참조·`mop1` 지문)가 들어가야 한다 —
     * 같은 키·다른 근거는 [AdminCommandResult.IdempotencyKeyReused](409).
     */
    @Suppress("LongParameterList")
    fun executeApproved(
        operation: AdminOperation,
        request: AdminRequest,
        evidence: ApprovalEvidence,
        window: ApprovalWindow,
        fingerprintFields: List<String?>,
        body: (Work) -> String,
    ): AdminCommandResult {
        val store = checkNotNull(evidences) { "approved commands need the approval evidence store" }
        val fingerprint = fingerprintOf(operation, fingerprintFields)
        return classified(operation, request) {
            transactions.execute(::isRetryable) { onceApproved(store, operation, request, fingerprint, evidence, window, body) }
        }
    }

    /**
     * 기한 확인 전용(§9.4 — 실행과 같은 operation·키·지문으로 예약, **업무 본문 없음**). 결과가 있으면 재생, 판정 1·2 저장(근거 행 `DEADLINE_CONFIRM`), 판정 3 저장(근거 행 없음),
     * MASTER 시각 < 기한이면 [DeadlineNotYetDue]로 TX 전체 rollback(예약 행도 남지 않음, 재시도 대상 아님 — m-c) 뒤 [DeadlineConfirmationResult.NotYetDue],
     * 기한 이상이면 `APPROVAL_EXPIRED` 저장 + 근거 행(`DEADLINE_CONFIRM`).
     */
    fun confirmDeadline(
        operation: AdminOperation,
        request: AdminRequest,
        evidence: ApprovalEvidence,
        window: ApprovalWindow,
        fingerprintFields: List<String?>,
    ): DeadlineConfirmationResult {
        val store = checkNotNull(evidences) { "deadline confirmation needs the approval evidence store" }
        val fingerprint = fingerprintOf(operation, fingerprintFields)
        return try {
            DeadlineConfirmationResult.Decided(
                classified(operation, request) { transactions.execute(::isRetryable) { onceDeadline(store, operation, request, fingerprint, evidence, window) } },
            )
        } catch (_: DeadlineNotYetDue) {
            DeadlineConfirmationResult.NotYetDue
        }
    }

    /** TX 실행 결과의 기술 실패 분류([execute]와 근거 명령 공통 — 동작은 이전 [execute]와 같다). */
    private fun <T> classified(operation: AdminOperation, request: AdminRequest, block: () -> T): T {
        return try {
            block()
        } catch (e: AdminCommandFailure) {
            throw e
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("admin command outcome unknown operation={} correlationId={} failure=commit", operation.code, request.correlationId)
            metrics.adminOutcomeUnknown(operation.code)
            throw AdminCommandFailure.OutcomeUnknown(e)
        } catch (e: CannotCreateTransactionException) {
            throw AdminCommandFailure.TemporarilyUnavailable(e)
        } catch (e: UnexpectedRollbackException) {
            throw AdminCommandFailure.TemporarilyUnavailable(e)
        } catch (e: PersistenceFailure) {
            throw classify(operation, request, e)
        }
    }

    /** 재시도 밖으로 나온 저장 실패의 분류. 재시도 대상이었다면 상한 소진이다. */
    private fun classify(operation: AdminOperation, request: AdminRequest, e: PersistenceFailure): AdminCommandFailure {
        if (isRetryable(e)) {
            log.warn(
                "admin command retries exhausted operation={} correlationId={} attempts={} failure={}",
                operation.code, request.correlationId, MAX_ATTEMPTS, failureKind(e),
            )
            metrics.adminRetriesExhausted(operation.code)
            return AdminCommandFailure.TemporarilyUnavailable(e)
        }
        if (e is PersistenceFailure.UniqueConflict && e.constraint in GENERATED_IDENTIFIERS) {
            log.warn(
                "admin command generated identifier collision operation={} correlationId={} constraint={}",
                operation.code, request.correlationId, e.constraint,
            )
            metrics.adminIdentifierCollision(operation.code)
            return AdminCommandFailure.IdentifierCollision(e.constraint.name)
        }
        if (e is PersistenceFailure.UniqueConflict && !e.constraint.businessKey) {
            // BUSINESS-PROFILE-001 §5.2 끝(M-4): 업무 유일키도 서버 생성 ID도 아닌 UNIQUE 위반(예: BUSINESS_PROFILE_ROW — diff·flush 순서 결함)은 재판정하지 않는다.
            // 결함 신호로 제약 분류 이름만 남긴다(키 값·입력 값 없음).
            log.warn("admin command store constraint defect operation={} correlationId={} constraint={}", operation.code, request.correlationId, e.constraint)
        }
        return AdminCommandFailure.TemporarilyUnavailable(e)
    }

    private fun failureKind(e: PersistenceFailure): String = when (e) {
        is PersistenceFailure.UniqueConflict -> "unique:${e.constraint}"
        else -> e::class.simpleName ?: "persistence"
    }

    private fun once(operation: AdminOperation, request: AdminRequest, fingerprint: String, body: (Work) -> String): AdminCommandResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val scope = AdminRequestScope(operation, request.idempotencyKey)
        val recordId = ids.newUuid()
        reserveOrReplay(scope, recordId, fingerprint, request, now)?.let { return it }

        val work = Work(operation, request, recordId)
        val result = try {
            StoredAdminResult.Succeeded(body(work))
        } catch (e: MasterRuleViolation) {
            check(!work.writing) { "a business rule was violated after writes started (decide before writing)" }
            StoredAdminResult.Rejected(e.rejection, e.reference)
        }
        requests.complete(scope, result, work.decidedAt ?: now)
        return present(result, replayed = false)
    }

    /** 멱등 기록 예약/잠금. 이미 커밋된 기록이면 재생(같은 fingerprint) 또는 키 재사용(다른 fingerprint) 결과, 새로 예약했으면 null. */
    private fun reserveOrReplay(scope: AdminRequestScope, recordId: String, fingerprint: String, request: AdminRequest, now: Instant): AdminCommandResult? {
        val reserved = try {
            requests.reserveOrLock(AdminReservation(scope, recordId, fingerprint, request.actor, now))
        } catch (e: PersistenceFailure.LockTimeout) {
            throw AdminCommandFailure.RequestInProgress(e)
        }
        if (reserved !is AdminReservationOutcome.Existing) return null
        val record = reserved.record
        if (record.requestFingerprint != fingerprint) return AdminCommandResult.IdempotencyKeyReused
        return present(checkNotNull(record.result) { "committed admin request has no result" }, replayed = true)
    }

    @Suppress("LongParameterList")
    private fun onceApproved(
        store: ApprovalEvidenceStore,
        operation: AdminOperation,
        request: AdminRequest,
        fingerprint: String,
        evidence: ApprovalEvidence,
        window: ApprovalWindow,
        body: (Work) -> String,
    ): AdminCommandResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val scope = AdminRequestScope(operation, request.idempotencyKey)
        val recordId = ids.newUuid()
        reserveOrReplay(scope, recordId, fingerprint, request, now)?.let { return it }

        val work = Work(operation, request, recordId, evidence)
        val result = try {
            evidence.admit(now, window)
            requireUnused(store, evidence)
            val reference = body(work)
            check(work.writing) { "an approved command body must decide and start writing before it succeeds" }
            StoredAdminResult.Succeeded(reference).also {
                store.record(evidenceRecord(evidence, operation, recordId, it, ApprovalDecidedVia.EXECUTE, work.now))
            }
        } catch (e: MasterRuleViolation) {
            check(!work.writing) { "a business rule was violated after writes started (decide before writing)" }
            warnIfAlerting(operation, request, e.rejection)
            StoredAdminResult.Rejected(e.rejection, e.reference).also {
                if (e.rejection != MasterRejection.APPROVAL_ID_ALREADY_USED) {
                    store.record(evidenceRecord(evidence, operation, recordId, it, ApprovalDecidedVia.EXECUTE, work.decidedAt ?: now))
                }
            }
        }
        requests.complete(scope, result, work.decidedAt ?: now)
        return present(result, replayed = false)
    }

    @Suppress("LongParameterList")
    private fun onceDeadline(
        store: ApprovalEvidenceStore,
        operation: AdminOperation,
        request: AdminRequest,
        fingerprint: String,
        evidence: ApprovalEvidence,
        window: ApprovalWindow,
    ): AdminCommandResult {
        val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
        val scope = AdminRequestScope(operation, request.idempotencyKey)
        val recordId = ids.newUuid()
        reserveOrReplay(scope, recordId, fingerprint, request, now)?.let { return it }

        val rejection = try {
            evidence.admit(now, window)
            requireUnused(store, evidence)
            if (!evidence.isDueAt(now)) throw DeadlineNotYetDue()
            MasterRejection.APPROVAL_EXPIRED
        } catch (e: MasterRuleViolation) {
            e.rejection
        }
        warnIfAlerting(operation, request, rejection)
        val result = StoredAdminResult.Rejected(rejection, null)
        if (rejection != MasterRejection.APPROVAL_ID_ALREADY_USED) {
            store.record(evidenceRecord(evidence, operation, recordId, result, ApprovalDecidedVia.DEADLINE_CONFIRM, now))
        }
        requests.complete(scope, result, now)
        return present(result, replayed = false)
    }

    /**
     * 판정 3(§9.3): 같은 approvalId의 근거 행이 이미 있으면(다른 operation — 같은 operation이면 같은 키라 예약 단계에서 재생) 1회 사용 위반. 잠금 없는 존재 조회
     * (WARN은 [warnIfAlerting]이 남긴다).
     */
    private fun requireUnused(store: ApprovalEvidenceStore, evidence: ApprovalEvidence) {
        if (store.find(evidence.approvalId) != null) throw MasterRuleViolation(MasterRejection.APPROVAL_ID_ALREADY_USED)
    }

    /** 경보 후보 거절(§9.8 Observability — 같은 행위자·1회 사용 위반·Revision 등록자 승인)은 WARN(연산·상관 ID·사유만 — 행위자·근거 참조 값 없음). */
    private fun warnIfAlerting(operation: AdminOperation, request: AdminRequest, rejection: MasterRejection) {
        if (rejection in ALERTING_REJECTIONS) {
            log.warn("approval evidence rejected operation={} correlationId={} rejection={}", operation.code, request.correlationId, rejection)
        }
    }

    private fun evidenceRecord(
        evidence: ApprovalEvidence,
        operation: AdminOperation,
        requestRecordId: String,
        result: StoredAdminResult,
        via: ApprovalDecidedVia,
        at: Instant,
    ): ApprovalEvidenceRecord = when (result) {
        is StoredAdminResult.Succeeded -> ApprovalEvidenceRecord(evidence, operation, requestRecordId, ApprovalOutcome.EXECUTED, null, result.reference, via, at)
        // 근거 행 거절에는 reference를 두지 않는다(DB CHECK — 거절 근거 대상 ID는 admin_request에만).
        is StoredAdminResult.Rejected -> ApprovalEvidenceRecord(evidence, operation, requestRecordId, ApprovalOutcome.REJECTED, result.rejection, null, via, at)
    }

    private fun present(result: StoredAdminResult, replayed: Boolean): AdminCommandResult = when (result) {
        is StoredAdminResult.Succeeded -> AdminCommandResult.Succeeded(result.reference, replayed)
        is StoredAdminResult.Rejected -> AdminCommandResult.Rejected(result.rejection, result.reference, replayed)
    }

    private fun isRetryable(e: RuntimeException): Boolean = when (e) {
        is PersistenceFailure.Deadlock, is PersistenceFailure.OptimisticConflict -> true
        is PersistenceFailure.UniqueConflict -> e.constraint.businessKey && e.constraint != StoreConstraint.ADMIN_REQUEST_SCOPE
        else -> false
    }

    /** 명령 한 번의 업무 본문 맥락: 시각·행위자·요청 기록 ID, 쓰기 단계 표시, 이력 + Outbox 기록. */
    inner class Work(
        val operation: AdminOperation,
        val request: AdminRequest,
        val requestRecordId: String,
        private val evidence: ApprovalEvidence? = null,
    ) {
        var writing: Boolean = false
            private set

        /** 직렬화 잠금 뒤 읽은 판정 시각(아직 안 읽었으면 null). */
        var decidedAt: Instant? = null
            private set

        /**
         * 이 명령의 판정·저장 시각. 본문이 필요한 잠금을 **모두 잡은 뒤** 처음 부를 때 서버 시계를 읽고(ms 버림) 이후 같은 값을 돌려준다(review n-1).
         */
        fun lockedNow(): Instant = decidedAt ?: clock.now().truncatedTo(ChronoUnit.MILLIS).also {
            decidedAt = it
            // 승인 근거 모드(§9.3 판정 4, m-4): 잠금 뒤 시각을 읽은 직후 만료 훅 — 본문은 이 훅을 직접 부르지 않는다.
            evidence?.requireNotExpired(it)
        }

        /** [lockedNow]로 정한 판정 시각. 잠금 전에 읽으면 결함이다. */
        val now: Instant get() = checkNotNull(decidedAt) { "read the decision time with lockedNow() after acquiring the locks" }

        /** domain 판정이 끝났다. 이후의 규칙 위반은 업무 거절이 아니라 결함이다. */
        fun beginWrites() {
            check(evidence == null || decidedAt != null) { "an approved command must read lockedNow() (expiry check) before writing" }
            writing = true
        }

        fun newId(prefix: String): String = ids.newId(prefix)

        /** 변경 이력(append-only)과 통합 이벤트(Outbox)를 같은 TX에 남긴다. [historyDetail]이 없으면 [payload]를 이력에도 쓴다. */
        @Suppress("LongParameterList")
        fun record(
            aggregateType: String,
            aggregateId: String,
            aggregateVersion: Long,
            eventType: String,
            payload: Map<String, Any?>,
            historyDetail: Map<String, Any?> = payload,
        ) {
            check(writing) { "records are written after the decision" }
            changeLog.append(
                ChangeRecord(requestRecordId, operation, aggregateType, aggregateId, eventType, request.actor, now, historyDetail),
            )
            outbox.append(
                OutboxEvent(
                    eventId = ids.newUuid(),
                    eventType = eventType,
                    schemaVersion = 1,
                    eventCategory = EVENT_CATEGORY,
                    producer = PRODUCER,
                    aggregateType = aggregateType,
                    aggregateId = aggregateId,
                    aggregateVersion = aggregateVersion,
                    occurredAt = now,
                    correlationId = request.correlationId,
                    causationId = requestRecordId,
                    effectKey = "$eventType:$aggregateId:$aggregateVersion",
                    payload = payload + ("occurredAt" to now),
                ),
            )
        }

        /**
         * 권리 입력 변경 뒤(같은 TX, 쓰기 단계) [merchant]의 현재 권리 Snapshot을 발행한다(revision은 본문이 이미 +1·저장했다). MID가 없는
         * Merchant는 발행하지 않는다(PAYMENT가 물을 일이 없다 — 명세 §3.2).
         */
        fun publishEntitlement(merchant: Merchant) {
            check(writing) { "the entitlement snapshot is published after the decision" }
            publisher.publishChange(merchant, now, request.correlationId, requestRecordId)
        }
    }

    companion object {
        const val EVENT_CATEGORY = "INTEGRATION_MASTER_REFERENCE"
        const val PRODUCER = "master-api"

        /** 교착·내부 경합 재시도 상한(기술 설정, 업무 정책 아님). */
        const val MAX_ATTEMPTS = 3

        /** 서버가 만든 식별자(업무 입력이 아님)의 UNIQUE/PK. 충돌하면 사용자 충돌이 아니라 [AdminCommandFailure.IdentifierCollision]. */
        private val GENERATED_IDENTIFIERS = setOf(StoreConstraint.IDENTIFIER, StoreConstraint.OUTBOX_EVENT_ID)

        /** 경보 후보 근거 거절(§9.3·§9.8). */
        private val ALERTING_REJECTIONS = setOf(
            MasterRejection.APPROVAL_SAME_ACTOR, MasterRejection.APPROVAL_ID_ALREADY_USED, MasterRejection.APPROVER_IS_REVISION_REGISTRANT,
            // MERCHANT-OPS-002 §9.27(B3-1F — D-MOPS-009 ③): ADMIN TX1이 먼저 막으므로 MASTER에서 나오면 2인 통제 우회 신호(경보 후보).
            MasterRejection.APPROVER_IS_ACTIVATION_REGISTRANT, MasterRejection.APPROVER_IS_SITE_REGISTRANT,
            // MERCHANT-CODE-001 MC-1b(§3.7.3 판정 10·§9 Observability): 요청 현재 코드 ≠ 저장 현재 코드는 정상 경로에서 불가능 — 결함 신호(코드 값은 로그에 넣지 않는다).
            MasterRejection.MERCHANT_CODE_MISMATCH,
        )

        /**
         * 요청 fingerprint `m1:` + SHA-256(연산 코드와 필드의 길이 접두 canonical 연결). null 필드는 `~`, 값은 `<UTF-8 byte 길이>:<값>`.
         * 입력은 이미 domain 값 객체를 통과한 well-formed 문자열이다.
         */
        fun fingerprintOf(operation: AdminOperation, fields: List<String?>): String {
            val canonical = buildString {
                (listOf(operation.code) + fields).forEach { field ->
                    if (field == null) append("~") else append(field.toByteArray(StandardCharsets.UTF_8).size).append(':').append(field)
                    append('|')
                }
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8))
            return "m1:" + HexFormat.of().formatHex(digest)
        }
    }
}

/**
 * 기한 확인 전용 신호(§9.4 ③, review m-c): MASTER 시각 < 실행 기한. [MasterRuleViolation]이 아니고(저장하지 않는다) 재시도 분류 대상도 아니다 — 실행기가 TX 전체를 rollback한 뒤
 * [DeadlineConfirmationResult.NotYetDue]로 바꾼다(그 키 `admin_request` 0행). 스택 추적 없음 — 제어 흐름 전용.
 */
internal class DeadlineNotYetDue : RuntimeException("approval deadline not yet due", null, false, false)
