package com.paycore.financial.acquiring.application

import com.paycore.financial.acquiring.application.provided.TransmissionResult
import com.paycore.financial.acquiring.application.provided.TransmissionResult.Kind
import com.paycore.financial.acquiring.application.provided.TransmitAcquisitionFileUseCase
import com.paycore.financial.acquiring.application.required.AcquirerChannelFailure
import com.paycore.financial.acquiring.application.required.AcquirerFileChannel
import com.paycore.financial.acquiring.application.required.AcquirerFileSession
import com.paycore.financial.acquiring.application.required.AcquiringClock
import com.paycore.financial.acquiring.application.required.AcquiringPersistenceFailure
import com.paycore.financial.acquiring.application.required.AcquisitionFileRepository
import com.paycore.financial.acquiring.application.required.AcquisitionTransmissionStore
import com.paycore.financial.acquiring.application.required.AcquisitionTransportPolicy
import com.paycore.financial.acquiring.application.required.TransmissionAttemptRecord
import com.paycore.financial.acquiring.application.required.TransmissionOutcomeRecord
import com.paycore.financial.acquiring.domain.AcquisitionContractViolation
import com.paycore.financial.acquiring.domain.AcquisitionEnvironment
import com.paycore.financial.acquiring.domain.AcquisitionFileFormat
import com.paycore.financial.acquiring.domain.AcquisitionFileId
import com.paycore.financial.acquiring.domain.NotClaimableReason
import com.paycore.financial.acquiring.domain.TransmissionClaim
import com.paycore.financial.acquiring.domain.TransmissionEvidence
import com.paycore.financial.acquiring.domain.TransmissionFailureReason
import com.paycore.financial.acquiring.domain.TransmissionOutcome
import com.paycore.financial.acquiring.domain.TransmissionState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * [TransmitAcquisitionFileUseCase] 구현(ACQUIRING-001 §4.5 — 2-D, rev 0.6 리뷰 반영). TX 두 개 사이에 외부 호출(SFTP)이 있고 잠금을 들고 기다리지 않는다:
 * ```
 * TX1(REQUIRES_NEW, READ COMMITTED): 파일 읽기 → 저장 바이트 무결성(재렌더링 대조, 봉인 재호출 없음 — NIT-1) → claimTransmission → update(@Version) + 시도 행
 * SFTP(TX 밖): 최종명 확인 → 없으면 [남은 lease ≥ 여유?] .{id}.dat.{attemptNo}.part 쓰기(핸들 닫음) → [남은 lease ≥ 여유?] 그 임시명 rename → 새 접속 되읽기 hash 대조
 * TX2: 파일 행만 다시 읽기(BLOB 없음) → version이 점유 뒤 값이 아니면 SUPERSEDED 관찰 행만(상태 불변) → completeTransmission → update(@Version) + 결과 행
 * ```
 * 판정(I-24 rev 0.6 — 리뷰 MJ-2): rename 응답 없음(Interrupted)은 재확인에서 없음이어도 **결과 모름**(NOT_FOUND만으로 미수신 단정 금지). NOT_DELIVERED는 rename을
 * 보내지 않았거나(쓰기 실패·lease 부족) 서버가 명시적으로 거절(임시 파일이 정리돼 NO_SUCH_FILE 포함)하고 최종명이 없을 때만.
 * 임시명은 시도별이다(C-9 — rev 0.8): 이어받은 시도와 늦게 깨어난 시도가 같은 임시 파일을 쓰지 않으므로, 늦은 시도가 rename해도 게시되는 것은 그 시도가 끝까지 쓴
 * 봉인 바이트뿐이다. 남은 lease 검사(I-34)는 서버 쓰기 부담과 불필요한 rename을 줄이는 보조 장치로 남는다.
 */
@Service
class AcquisitionTransmissionService(
    transactionManager: PlatformTransactionManager,
    private val files: AcquisitionFileRepository,
    private val attempts: AcquisitionTransmissionStore,
    private val channel: AcquirerFileChannel,
    private val transport: AcquisitionTransportPolicy,
    private val clock: AcquiringClock,
) : TransmitAcquisitionFileUseCase {

    private val transactions = AcquiringLocalTransactions(transactionManager, 1)
    private val log = LoggerFactory.getLogger(AcquisitionTransmissionService::class.java)

    override fun transmitPending(acquirerCode: String, environment: String, limit: Int): List<TransmissionResult> {
        require(limit in 1..MAX_BATCH) { "limit must be within 1..$MAX_BATCH" }
        requireNoActiveTransaction()
        val env = AcquisitionEnvironment.entries.firstOrNull { it.name == environment } ?: throw IllegalArgumentException("environment must be TEST or LIVE")
        val now = clock.now()
        val ids = transactions.execute({ false }) { files.transmittable(acquirerCode, env, now, limit) }
        // 리뷰 m-1: 한 파일의 예기치 않은 실패가 나머지 회복을 멈추지 않는다.
        return ids.map { id ->
            try {
                transmit(id.value)
            } catch (e: RuntimeException) {
                log.error("acquisition transmission failed unexpectedly fileId={} failure={}", id, e.javaClass.simpleName)
                TransmissionResult(id.value, Kind.ERROR, null, e.javaClass.simpleName)
            }
        }
    }

    override fun transmit(fileId: String): TransmissionResult {
        requireNoActiveTransaction()
        val id = try {
            AcquisitionFileId.parse(fileId)
        } catch (e: AcquisitionContractViolation) {
            return TransmissionResult(fileId, Kind.NOT_FOUND, null, "file id grammar")
        }
        // 리뷰 m-3: REAL/Simulator 격리 이중 방어 — LIVE 파일은 봉인부터 막혀 있지만(D-ACQ-008) 전송도 fail-closed로 거부한다(접속 설정은 매입사 코드 단위).
        if (id.environment != AcquisitionEnvironment.TEST) {
            log.error("acquisition transmission of a LIVE file refused fileId={} (D-ACQ-008)", id)
            return TransmissionResult(id.value, Kind.LIVE_BLOCKED, null, null)
        }
        if (!channel.isConfigured(id.acquirerCode)) {
            log.error("acquisition transmission not configured fileId={} — not transmitting (fail-closed)", id)
            return TransmissionResult(id.value, Kind.NOT_CONFIGURED, null, null)
        }
        val claim = when (val c = claim(id)) {
            is ClaimStep.Done -> return c.result
            is ClaimStep.Go -> c
        }
        val outcome = deliver(id, claim)
        return complete(id, claim, outcome)
    }

    /** 리뷰 NIT: 외부 호출을 감싸는 호출자 TX가 있으면 잠금·연결을 SFTP 동안 들고 있게 된다 — 거부한다. */
    private fun requireNoActiveTransaction() {
        check(!TransactionSynchronizationManager.isActualTransactionActive()) { "acquisition transmission must not run inside a transaction" }
    }

    private sealed interface ClaimStep {
        data class Done(val result: TransmissionResult) : ClaimStep

        class Go(
            val attemptNo: Int, val claimedVersion: Long, val resumeState: TransmissionState, val content: ByteArray, val sha256: String, val leaseUntil: Instant,
        ) : ClaimStep
    }

    private fun claim(id: AcquisitionFileId): ClaimStep = try {
        transactions.execute({ false }) {
            val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
            val stored = files.find(id) ?: return@execute ClaimStep.Done(TransmissionResult(id.value, Kind.NOT_FOUND, null, null))
            if (!stored.intact()) {
                log.error("acquisition file integrity violation fileId={} — stored bytes differ from the stored header and records; not transmitting", id)
                return@execute ClaimStep.Done(TransmissionResult(id.value, Kind.INTEGRITY_VIOLATION, stored.file.transmissionState.name, null))
            }
            when (val c = stored.file.claimTransmission(now, now.plus(transport.transmissionLease))) {
                is TransmissionClaim.NotClaimable -> ClaimStep.Done(
                    TransmissionResult(
                        id.value,
                        when (c.reason) {
                            NotClaimableReason.IN_FLIGHT -> Kind.IN_FLIGHT
                            NotClaimableReason.ALREADY_TRANSMITTED -> Kind.ALREADY_TRANSMITTED
                            NotClaimableReason.QUARANTINED -> Kind.QUARANTINED
                        },
                        stored.file.transmissionState.name, null,
                    ),
                )
                is TransmissionClaim.Claimed -> {
                    val leaseUntil = checkNotNull(c.file.transmissionLeaseUntil)
                    files.update(c.file, now)
                    val attemptNo = attempts.nextAttemptNo(id)
                    attempts.appendAttempt(TransmissionAttemptRecord(id, attemptNo, c.resumeState, stored.file.contentSha256, now, leaseUntil))
                    ClaimStep.Go(attemptNo, c.file.version + 1, c.resumeState, stored.content, stored.file.contentSha256, leaseUntil)
                }
            }
        }
    } catch (e: AcquisitionContractViolation) {
        // 리뷰 NIT-3(2-F): 저장 행이 계약(문법·불변식)을 어겨 복원할 수 없다 — 점유하지 않고 무결성 위반으로 분류한다(TX는 rollback).
        log.error("acquisition file integrity violation fileId={} — stored rows violate the file v1 contract; not transmitting", id)
        ClaimStep.Done(TransmissionResult(id.value, Kind.INTEGRITY_VIOLATION, null, null))
    } catch (e: AcquiringPersistenceFailure.OptimisticConflict) {
        ClaimStep.Done(TransmissionResult(id.value, Kind.IN_FLIGHT, null, "claimed concurrently"))
    } catch (e: AcquiringPersistenceFailure.UniqueConflict) {
        ClaimStep.Done(TransmissionResult(id.value, Kind.IN_FLIGHT, null, "claimed concurrently"))
    } catch (e: AcquiringCommitOutcomeUnknownException) {
        // 점유 커밋 결과 모름: 점유됐다면 lease 만료 뒤 회복이 확인부터 이어받는다. 이 호출은 SFTP를 하지 않는다.
        log.warn("acquisition transmission claim commit outcome unknown fileId={}", id)
        ClaimStep.Done(TransmissionResult(id.value, Kind.ERROR, null, "CLAIM_COMMIT_OUTCOME_UNKNOWN"))
    }

    /** rename 안전 여유가 남았는가(리뷰 MJ-1 (b)). lease가 지나면 다른 시도가 같은 임시명을 다시 열 수 있다. */
    private fun leaseAllows(claim: ClaimStep.Go, margin: Duration): Boolean = clock.now().plus(margin).isBefore(claim.leaseUntil)

    /** TX 밖 SFTP 절차. 예외를 결과로 바꾼다(키·지문·경로 원문을 로그에 남기지 않는다). */
    private fun deliver(id: AcquisitionFileId, claim: ClaimStep.Go): TransmissionOutcome {
        var connected = false
        if (claim.attemptNo > MAX_ATTEMPT_NO) {
            // 서버 계약(rev 0.7 §5.1)의 임시명 n은 1..999999 — 넘으면 접속하지 않는다(운영 확인 필요, 사실상 도달 불가).
            log.error("acquisition transmission attempt number exceeds the temp name grammar fileId={} attempt={}", id, claim.attemptNo)
            return TransmissionOutcome.NotDelivered(TransmissionFailureReason.INTERNAL_ERROR)
        }
        return try {
            val margin = transport.renameSafetyMargin(id.acquirerCode)
            val session = try {
                channel.open(id.acquirerCode)
            } catch (e: AcquirerChannelFailure) {
                return TransmissionOutcome.NotDelivered(connectReason(id, e))
            }
            connected = true
            upload(id, claim, session, margin)
        } catch (e: RuntimeException) {
            // 리뷰 m-1: 채널 밖 예외가 TX 밖으로 새지 않게 결과로 바꾼다. 접속 전 = 아무것도 보내지 않음, 접속 뒤 = 무엇을 보냈는지 모름.
            log.error("acquisition transmission internal failure fileId={} connected={} failure={}", id, connected, e.javaClass.simpleName)
            if (connected) TransmissionOutcome.Unknown(TransmissionFailureReason.INTERNAL_ERROR)
            else TransmissionOutcome.NotDelivered(TransmissionFailureReason.CONNECT_FAILED)
        }
    }

    private fun upload(id: AcquisitionFileId, claim: ClaimStep.Go, opened: AcquirerFileSession, margin: Duration): TransmissionOutcome {
        val finalName = id.fileName
        // C-9(Q-2D-1): 시도마다 다른 임시명 — 늦게 깨어난 시도는 자기 시도 번호의 임시 파일(자기가 끝까지 쓴 봉인 바이트)만 rename할 수 있다.
        val tempName = tempName(finalName, claim.attemptNo)
        var renamed: Boolean?
        opened.use { s ->
            when (check(s, finalName, claim.sha256)) {
                Check.MATCH -> return TransmissionOutcome.Delivered(TransmissionEvidence.VERIFIED_FINAL_PRESENT)
                Check.MISMATCH -> return mismatch(id)
                Check.FAILED -> return TransmissionOutcome.NotDelivered(TransmissionFailureReason.VERIFY_FAILED)
                Check.ABSENT -> Unit
            }
            if (!leaseAllows(claim, margin)) return leaseExhausted(id)
            try {
                s.writeInbox(tempName, claim.content)
            } catch (e: AcquirerChannelFailure) {
                // rename을 보내기 전: 기관은 최종명만 처리하므로(§7) 이 시도는 전달하지 않았다.
                return TransmissionOutcome.NotDelivered(TransmissionFailureReason.UPLOAD_FAILED)
            }
            // 리뷰 MJ-1 (b): rename 직전 남은 lease 확인 — 모자라면 rename을 보내지 않는다(.part는 다음 시도가 덮어쓴다).
            if (!leaseAllows(claim, margin)) return leaseExhausted(id)
            renamed = try {
                s.renameInbox(tempName, finalName)
                true
            } catch (e: AcquirerChannelFailure.Refused) {
                false
            } catch (e: AcquirerChannelFailure) {
                null
            }
        }
        // 재접속 확인(I-9): rename 응답과 무관하게 새 접속에서 최종명 바이트를 다시 읽어 봉인 hash와 대조한다.
        val verified = try {
            channel.open(id.acquirerCode).use { check(it, finalName, claim.sha256) }
        } catch (e: AcquirerChannelFailure) {
            Check.FAILED
        }
        return judge(id, renamed, verified)
    }

    /** I-24 판정표(rev 0.6). */
    private fun judge(id: AcquisitionFileId, renamed: Boolean?, verified: Check): TransmissionOutcome = when (verified) {
        Check.MATCH -> TransmissionOutcome.Delivered(if (renamed == true) TransmissionEvidence.RENAMED_AND_VERIFIED else TransmissionEvidence.VERIFIED_FINAL_PRESENT)
        Check.MISMATCH -> mismatch(id)
        Check.ABSENT -> when (renamed) {
            true -> {
                log.error("acquisition file renamed but absent on re-check fileId={} — outcome unknown, reconcile", id)
                TransmissionOutcome.Unknown(TransmissionFailureReason.RENAMED_BUT_ABSENT)
            }
            false -> TransmissionOutcome.NotDelivered(TransmissionFailureReason.RENAME_REFUSED)
            // 리뷰 MJ-2: rename 응답이 없으면 없음 확인도 미수신 근거가 아니다.
            null -> TransmissionOutcome.Unknown(TransmissionFailureReason.RENAME_OUTCOME_UNKNOWN)
        }
        Check.FAILED ->
            if (renamed == true) TransmissionOutcome.Delivered(TransmissionEvidence.RENAMED)
            else TransmissionOutcome.Unknown(TransmissionFailureReason.RENAME_OUTCOME_UNKNOWN)
    }

    private enum class Check { MATCH, MISMATCH, ABSENT, FAILED }

    private fun check(session: AcquirerFileSession, name: String, sha256: String): Check = try {
        if (session.inboxSize(name) == null) Check.ABSENT
        else if (AcquisitionFileFormat.sha256(session.readInbox(name)) == sha256) Check.MATCH
        else Check.MISMATCH
    } catch (e: AcquirerChannelFailure) {
        Check.FAILED
    }

    private fun leaseExhausted(id: AcquisitionFileId): TransmissionOutcome {
        log.warn("acquisition transmission lease nearly exhausted fileId={} — not renaming", id)
        return TransmissionOutcome.NotDelivered(TransmissionFailureReason.LEASE_EXHAUSTED)
    }

    private fun mismatch(id: AcquisitionFileId): TransmissionOutcome {
        log.error("acquisition file id already holds different content at the acquirer fileId={} — quarantined, not overwriting", id)
        return TransmissionOutcome.ContentMismatch
    }

    private fun connectReason(id: AcquisitionFileId, e: AcquirerChannelFailure): TransmissionFailureReason = when (e) {
        is AcquirerChannelFailure.HostKeyRejected -> {
            log.error("acquirer host key rejected fileId={} — not retrying, check the pinned host key", id)
            TransmissionFailureReason.HOST_KEY_REJECTED
        }
        is AcquirerChannelFailure.AuthenticationFailed -> {
            log.error("acquirer authentication failed fileId={}", id)
            TransmissionFailureReason.AUTHENTICATION_FAILED
        }
        else -> {
            log.warn("acquirer connect failed fileId={} failure={}", id, e.javaClass.simpleName)
            TransmissionFailureReason.CONNECT_FAILED
        }
    }

    private fun complete(id: AcquisitionFileId, claim: ClaimStep.Go, outcome: TransmissionOutcome): TransmissionResult = try {
        transactions.execute({ false }) {
            val now = clock.now().truncatedTo(ChronoUnit.MILLIS)
            // 리뷰 NIT: 파일 행만 읽는다(봉인 바이트·레코드 없음).
            val file = checkNotNull(files.findFile(id)) { "a claimed file exists" }
            if (file.version != claim.claimedVersion || file.transmissionState != TransmissionState.TRANSMITTING) {
                // 리뷰 m-5: 상태는 바꾸지 않고 이 시도의 관찰만 남긴다.
                attempts.appendOutcome(TransmissionOutcomeRecord(id, claim.attemptNo, outcome, file.transmissionState, now, superseded = true))
                return@execute superseded(id, file.transmissionState)
            }
            val next = file.completeTransmission(outcome, claim.resumeState, now)
            files.update(next, now)
            attempts.appendOutcome(TransmissionOutcomeRecord(id, claim.attemptNo, outcome, next.transmissionState, now))
            val kind = when (outcome) {
                is TransmissionOutcome.Delivered -> Kind.TRANSMITTED
                is TransmissionOutcome.NotDelivered -> Kind.NOT_DELIVERED
                is TransmissionOutcome.Unknown -> Kind.OUTCOME_UNKNOWN
                TransmissionOutcome.ContentMismatch -> Kind.QUARANTINED
            }
            val detail = when (outcome) {
                is TransmissionOutcome.Delivered -> outcome.evidence.name
                is TransmissionOutcome.NotDelivered -> outcome.reason.name
                is TransmissionOutcome.Unknown -> outcome.reason.name
                TransmissionOutcome.ContentMismatch -> null
            }
            log.info("acquisition transmission attempt fileId={} attempt={} outcome={} detail={} state={}", id, claim.attemptNo, outcome.code, detail, next.transmissionState)
            TransmissionResult(id.value, kind, next.transmissionState.name, detail)
        }
    } catch (e: AcquiringPersistenceFailure.OptimisticConflict) {
        // 같은 순간 다른 시도가 먼저 바꿨다: 관찰 행은 새 TX에서 남긴다(실패해도 상태에는 영향 없음 — 경고만).
        runCatching {
            transactions.execute({ false }) {
                val file = checkNotNull(files.findFile(id))
                attempts.appendOutcome(TransmissionOutcomeRecord(id, claim.attemptNo, outcome, file.transmissionState, clock.now().truncatedTo(ChronoUnit.MILLIS), true))
            }
        }.onFailure { log.warn("acquisition transmission superseded observation not recorded fileId={} failure={}", id, it.javaClass.simpleName) }
        superseded(id, null)
    } catch (e: AcquiringCommitOutcomeUnknownException) {
        // 결과 커밋 결과 모름: 커밋됐으면 다음 호출이 ALREADY_TRANSMITTED 등으로 보고, 안 됐으면 TRANSMITTING이 lease 만료 뒤 확인부터 회복한다.
        log.warn("acquisition transmission outcome commit unknown fileId={} attempt={} observed={}", id, claim.attemptNo, outcome.code)
        TransmissionResult(id.value, Kind.OUTCOME_UNKNOWN, null, "COMMIT_OUTCOME_UNKNOWN")
    }

    private fun superseded(id: AcquisitionFileId, state: TransmissionState?): TransmissionResult {
        log.warn("acquisition transmission attempt superseded fileId={} — its lease expired and another attempt took over", id)
        return TransmissionResult(id.value, Kind.SUPERSEDED, state?.name, null)
    }

    internal companion object {
        const val MAX_BATCH = 100

        /** 서버 계약의 임시명 시도 번호 상한(`[1-9][0-9]{0,5}`). */
        const val MAX_ATTEMPT_NO = 999_999

        /** 업로드 임시명 `.{fileId}.dat.{attemptNo}.part`(명세 rev 0.7 §5.1 — C-9). 옛 공용 임시명 `.{fileId}.dat.part`는 쓰지 않는다. */
        fun tempName(finalName: String, attemptNo: Int): String {
            require(attemptNo in 1..MAX_ATTEMPT_NO) { "attempt number outside the temp name grammar" }
            return ".$finalName.$attemptNo.part"
        }
    }
}
