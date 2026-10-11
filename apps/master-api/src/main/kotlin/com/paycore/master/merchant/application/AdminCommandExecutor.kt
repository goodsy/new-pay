package com.paycore.master.merchant.application

import com.paycore.master.merchant.application.provided.AdminCommandFailure
import com.paycore.master.merchant.application.provided.AdminCommandResult
import com.paycore.master.merchant.application.provided.AdminOperation
import com.paycore.master.merchant.application.provided.AdminRequest
import com.paycore.master.merchant.application.required.AdminRequestScope
import com.paycore.master.merchant.application.required.AdminRequestStore
import com.paycore.master.merchant.application.required.AdminReservation
import com.paycore.master.merchant.application.required.AdminReservationOutcome
import com.paycore.master.merchant.application.required.ChangeRecord
import com.paycore.master.merchant.application.required.MasterChangeLog
import com.paycore.master.merchant.application.required.MasterIdGenerator
import com.paycore.master.merchant.application.required.OutboxEvent
import com.paycore.master.merchant.application.required.OutboxStore
import com.paycore.master.merchant.application.required.PersistenceFailure
import com.paycore.master.merchant.application.required.StoreConstraint
import com.paycore.master.merchant.application.required.StoredAdminResult
import com.paycore.master.merchant.domain.MasterRuleViolation
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
 */
internal class AdminCommandExecutor(
    transactionManager: PlatformTransactionManager,
    private val requests: AdminRequestStore,
    private val changeLog: MasterChangeLog,
    private val outbox: OutboxStore,
    private val ids: MasterIdGenerator,
    private val clock: MasterClock,
) {
    private val transactions = LocalTransactions(transactionManager, MAX_ATTEMPTS)
    private val log = LoggerFactory.getLogger(AdminCommandExecutor::class.java)

    fun execute(operation: AdminOperation, request: AdminRequest, fingerprintFields: List<String?>, body: (Work) -> String): AdminCommandResult {
        val fingerprint = fingerprintOf(operation, fingerprintFields)
        return try {
            transactions.execute(::isRetryable) { once(operation, request, fingerprint, body) }
        } catch (e: AdminCommandFailure) {
            throw e
        } catch (e: CommitOutcomeUnknownException) {
            log.warn("admin command outcome unknown operation={} correlationId={} failure=commit", operation.code, request.correlationId)
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
            return AdminCommandFailure.TemporarilyUnavailable(e)
        }
        if (e is PersistenceFailure.UniqueConflict && e.constraint in GENERATED_IDENTIFIERS) {
            log.warn(
                "admin command generated identifier collision operation={} correlationId={} constraint={}",
                operation.code, request.correlationId, e.constraint,
            )
            return AdminCommandFailure.IdentifierCollision(e.constraint.name)
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
        val reserved = try {
            requests.reserveOrLock(AdminReservation(scope, recordId, fingerprint, request.actor, now))
        } catch (e: PersistenceFailure.LockTimeout) {
            throw AdminCommandFailure.RequestInProgress(e)
        }
        if (reserved is AdminReservationOutcome.Existing) {
            val record = reserved.record
            if (record.requestFingerprint != fingerprint) return AdminCommandResult.IdempotencyKeyReused
            return present(checkNotNull(record.result) { "committed admin request has no result" }, replayed = true)
        }

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
    inner class Work(val operation: AdminOperation, val request: AdminRequest, val requestRecordId: String) {
        var writing: Boolean = false
            private set

        /** 직렬화 잠금 뒤 읽은 판정 시각(아직 안 읽었으면 null). */
        var decidedAt: Instant? = null
            private set

        /**
         * 이 명령의 판정·저장 시각. 본문이 필요한 잠금을 **모두 잡은 뒤** 처음 부를 때 서버 시계를 읽고(ms 버림) 이후 같은 값을 돌려준다(review n-1).
         */
        fun lockedNow(): Instant = decidedAt ?: clock.now().truncatedTo(ChronoUnit.MILLIS).also { decidedAt = it }

        /** [lockedNow]로 정한 판정 시각. 잠금 전에 읽으면 결함이다. */
        val now: Instant get() = checkNotNull(decidedAt) { "read the decision time with lockedNow() after acquiring the locks" }

        /** domain 판정이 끝났다. 이후의 규칙 위반은 업무 거절이 아니라 결함이다. */
        fun beginWrites() {
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
    }

    companion object {
        const val EVENT_CATEGORY = "INTEGRATION_MASTER_REFERENCE"
        const val PRODUCER = "master-api"

        /** 교착·내부 경합 재시도 상한(기술 설정, 업무 정책 아님). */
        const val MAX_ATTEMPTS = 3

        /** 서버가 만든 식별자(업무 입력이 아님)의 UNIQUE/PK. 충돌하면 사용자 충돌이 아니라 [AdminCommandFailure.IdentifierCollision]. */
        private val GENERATED_IDENTIFIERS = setOf(StoreConstraint.IDENTIFIER, StoreConstraint.OUTBOX_EVENT_ID)

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
