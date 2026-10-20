package com.paycore.simulator.van.adapter.integration

import com.paycore.simulator.van.application.provided.AuthorizeAtVanUseCase
import com.paycore.simulator.van.application.provided.CancelAtVanUseCase
import com.paycore.simulator.van.application.provided.InquireCancelAtVanUseCase
import com.paycore.simulator.van.application.provided.InquireAtVanUseCase
import com.paycore.simulator.van.application.provided.VanAuthorizationResult
import com.paycore.simulator.van.application.provided.VanCancelInquiryRequest
import com.paycore.simulator.van.application.provided.VanCancelInquiryResult
import com.paycore.simulator.van.application.provided.VanCancelResult
import com.paycore.simulator.van.application.provided.VanInquiryRequest
import com.paycore.simulator.van.application.provided.VanInquiryResult
import com.paycore.simulator.van.application.required.VanClock
import com.paycore.simulator.van.domain.VanApproval
import com.paycore.simulator.van.domain.VanBehaviour
import com.paycore.simulator.van.domain.VanCancelBehaviour
import com.paycore.simulator.van.domain.VanCancelEntry
import com.paycore.simulator.van.domain.VanCancelTerms
import com.paycore.simulator.van.domain.VanDecisionOutcome
import com.paycore.simulator.van.domain.VanLedgerEntry
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 가상 VAN TCP 서버(AUTH-001 §5.1 — JDK `ServerSocket` + 연결당 virtual thread, 새 의존성 없음 AU-11). 한 연결에서 프레임을 차례로 읽고 각 요청에 응답한다(PG Adapter v1은
 * 요청당 연결 1개 — AU-8). 원장 결정은 Use Case가 커밋한 뒤에만 응답한다. MAC 불일치·모르는 키·스키마 위반·TEST 아님·등록 안 된 계정은 기록 없이 연결을 닫는다(n-4).
 * 새 결정의 첫 응답에는 가상 카드 전송 시나리오(늦은 응답·응답 없이 끊기·분할·중복·MAC 오류)를 적용한다. 요청·응답 원문은 로그에 쓰지 않는다.
 */
@Component
class VanTcpServer(
    private val settings: VanSettings,
    private val authorize: AuthorizeAtVanUseCase,
    private val inquire: InquireAtVanUseCase,
    private val cancel: CancelAtVanUseCase,
    private val inquireCancel: InquireCancelAtVanUseCase,
    private val clock: VanClock,
    private val environment: Environment,
) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(VanTcpServer::class.java)

    @Volatile
    private var server: ServerSocket? = null
    private var workers: ExecutorService? = null

    fun localPort(): Int = checkNotNull(server) { "the van server is not running" }.localPort

    override fun start() {
        val httpPort = environment.getProperty("server.port")?.toIntOrNull()
        check(settings.port == 0 || settings.port != httpPort) { "the van port must differ from the HTTP port" }
        val socket = ServerSocket(settings.port, BACKLOG)
        server = socket
        val pool = Executors.newVirtualThreadPerTaskExecutor()
        workers = pool
        Thread.ofVirtual().name("van-accept").start { acceptLoop(socket, pool) }
        log.info("virtual VAN listening on port {}", socket.localPort)
    }

    override fun stop() {
        server?.close()
        server = null
        workers?.let {
            it.shutdownNow()
            it.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        workers = null
    }

    override fun isRunning(): Boolean = server != null

    private fun acceptLoop(socket: ServerSocket, pool: ExecutorService) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: IOException) {
                break
            }
            pool.submit { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        // 검토 #2 n-2: 어떤 실패도 연결 스레드를 잡히지 않은 예외로 끝내지 않는다 — 기록 없이 닫는다.
        try {
            serveFrames(socket)
        } catch (e: Exception) {
            log.debug("van connection closed after a failure: {}", e::class.simpleName)
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun serveFrames(socket: Socket) {
        socket.use { s ->
            s.soTimeout = settings.idleTimeout.toMillis().coerceAtLeast(1).toInt()
            val input = s.getInputStream()
            val output = s.getOutputStream()
            while (true) {
                val frame = try {
                    VanFrameCodec.read(input, settings.keyRing)
                } catch (e: VanFrameException) {
                    if (e.failure != VanFrameFailure.CLOSED) log.debug("van frame refused: {}", e.failure)
                    return
                } catch (e: IOException) {
                    return // 유휴 timeout(SocketTimeoutException)·연결 끊김
                }
                val message = try {
                    VanMessages.parseRequest(frame.payload)
                } catch (e: VanFrameException) {
                    log.debug("van message refused: {}", e.failure)
                    return
                }
                val keepOpen = try {
                    when (message) {
                        is VanInbound.Auth -> handleAuth(frame, message, output)
                        is VanInbound.Inquiry -> handleInquiry(frame, message, output)
                        is VanInbound.Cancel -> handleCancel(frame, message, output)
                        is VanInbound.CancelInquiry -> handleCancelInquiry(frame, message, output)
                    }
                } catch (e: IOException) {
                    false
                }
                if (!keepOpen) return
            }
        }
    }

    /** @return 연결을 계속 쓸지. */
    private fun handleAuth(frame: VanFrame, m: VanInbound.Auth, out: OutputStream): Boolean {
        val r = m.request
        val result = authorize.authorize(frame.clientId, r)
        val now = clock.now()
        fun respond(outcome: VanMessages.AuthOutcome, entry: VanLedgerEntry? = null, pendingId: String? = null) = VanMessages.authResponse(
            inReplyTo = m.messageId, requestId = r.requestId, outcome = outcome, decisionId = entry?.entryId, pendingId = pendingId,
            approval = entry?.approval?.let(::approval), declineCategory = entry?.declineCategory?.name, respondedAt = now,
        )
        return when (result) {
            is VanAuthorizationResult.Decided -> {
                val outcome = if (result.entry.outcome == VanDecisionOutcome.APPROVED) VanMessages.AuthOutcome.APPROVED else VanMessages.AuthOutcome.DECLINED
                transport(frame, respond(outcome, result.entry), result.behaviour, out)
            }
            is VanAuthorizationResult.Pending -> write(out, frame, respond(VanMessages.AuthOutcome.PENDING, pendingId = result.pendingId))
            VanAuthorizationResult.Conflict -> write(out, frame, respond(VanMessages.AuthOutcome.REQUEST_CONFLICT))
            VanAuthorizationResult.RejectedClosed -> write(out, frame, respond(VanMessages.AuthOutcome.REJECTED_CLOSED))
            VanAuthorizationResult.CloseWithoutRecord -> false
        }
    }

    private fun handleInquiry(frame: VanFrame, m: VanInbound.Inquiry, out: OutputStream): Boolean {
        val result = inquire.inquire(frame.clientId, VanInquiryRequest(m.inquiryId, m.originalRequestId, m.institutionAccount, m.environment, m.closeIfAbsent))
        val now = clock.now()
        fun respond(r: VanMessages.InquiryResult, entry: VanLedgerEntry? = null, pendingId: String? = null, closureId: String? = null) = VanMessages.inquiryResponse(
            inReplyTo = m.messageId, inquiryId = m.inquiryId, originalRequestId = m.originalRequestId, result = r, pendingId = pendingId, decisionId = entry?.entryId,
            outcome = entry?.outcome, approval = entry?.approval?.let(::approval), declineCategory = entry?.declineCategory?.name, closureId = closureId, respondedAt = now,
        )
        val payload = when (result) {
            is VanInquiryResult.Found -> respond(VanMessages.InquiryResult.FOUND, entry = result.entry)
            is VanInquiryResult.Pending -> respond(VanMessages.InquiryResult.PENDING, pendingId = result.pendingId)
            is VanInquiryResult.NoEffectConfirmed -> respond(VanMessages.InquiryResult.NO_EFFECT_CONFIRMED, closureId = result.closureId)
            VanInquiryResult.NotFound -> respond(VanMessages.InquiryResult.NOT_FOUND)
            VanInquiryResult.Unavailable -> respond(VanMessages.InquiryResult.UNAVAILABLE)
            VanInquiryResult.CloseWithoutRecord -> return false
        }
        return write(out, frame, payload)
    }

    /** sim-van-2 취소(CANCEL-001 §6.3). 결정은 Use Case가 원장에 커밋한 뒤에만 응답한다. */
    private fun handleCancel(frame: VanFrame, m: VanInbound.Cancel, out: OutputStream): Boolean {
        val r = m.request
        val result = cancel.cancel(frame.clientId, r)
        val now = clock.now()
        fun respond(outcome: VanMessages.AuthOutcome, entry: VanCancelEntry? = null) = VanMessages.cancelResponse(
            inReplyTo = m.messageId, requestId = r.requestId, outcome = outcome, decisionId = entry?.entryId, pendingId = null,
            terms = entry?.terms?.let(::cancelTerms), declineCategory = entry?.declineCategory?.name, respondedAt = now,
        )
        return when (result) {
            is VanCancelResult.Decided -> {
                val outcome = if (result.entry.outcome == VanDecisionOutcome.APPROVED) VanMessages.AuthOutcome.APPROVED else VanMessages.AuthOutcome.DECLINED
                transport(frame, respond(outcome, result.entry), transportOf(result.behaviour), out)
            }
            VanCancelResult.Conflict -> write(out, frame, respond(VanMessages.AuthOutcome.REQUEST_CONFLICT))
            VanCancelResult.RejectedClosed -> write(out, frame, respond(VanMessages.AuthOutcome.REJECTED_CLOSED))
            VanCancelResult.CloseWithoutRecord -> false
        }
    }

    private fun handleCancelInquiry(frame: VanFrame, m: VanInbound.CancelInquiry, out: OutputStream): Boolean {
        val result = inquireCancel.inquire(
            frame.clientId, VanCancelInquiryRequest(m.inquiryId, m.originalRequestId, m.institutionAccount, m.environment, m.closeIfAbsent),
        )
        val now = clock.now()
        fun respond(r: VanMessages.InquiryResult, entry: VanCancelEntry? = null, closureId: String? = null) = VanMessages.cancelInquiryResponse(
            inReplyTo = m.messageId, inquiryId = m.inquiryId, originalRequestId = m.originalRequestId, result = r, pendingId = null, decisionId = entry?.entryId,
            outcome = entry?.outcome, terms = entry?.terms?.let(::cancelTerms), declineCategory = entry?.declineCategory?.name, closureId = closureId, respondedAt = now,
        )
        val payload = when (result) {
            is VanCancelInquiryResult.Found -> respond(VanMessages.InquiryResult.FOUND, entry = result.entry)
            is VanCancelInquiryResult.NoEffectConfirmed -> respond(VanMessages.InquiryResult.NO_EFFECT_CONFIRMED, closureId = result.closureId)
            VanCancelInquiryResult.NotFound -> respond(VanMessages.InquiryResult.NOT_FOUND)
            VanCancelInquiryResult.Unavailable -> respond(VanMessages.InquiryResult.UNAVAILABLE)
            VanCancelInquiryResult.CloseWithoutRecord -> return false
        }
        return write(out, frame, payload)
    }

    /** 취소 카드 동작 → 첫 응답 전송 동작(같은 전송 코드 경로를 쓴다). 판정 동작(거절·부분·조회 UNAVAILABLE·기록 전 끊김)은 전송 동작이 아니다. */
    private fun transportOf(b: VanCancelBehaviour): VanBehaviour = when (b) {
        VanCancelBehaviour.LATE_RESPONSE -> VanBehaviour.LATE_RESPONSE
        VanCancelBehaviour.DROP_AFTER -> VanBehaviour.DROP_AFTER_APPROVE
        VanCancelBehaviour.BAD_MAC -> VanBehaviour.BAD_MAC
        VanCancelBehaviour.FRAGMENTED -> VanBehaviour.FRAGMENTED
        else -> VanBehaviour.NORMAL
    }

    private fun cancelTerms(t: VanCancelTerms) = VanMessages.CancelTerms(
        t.cancelApprovalNumber, t.providerTransactionId, t.originalProviderTransactionId, t.cancelledAmountMinor, t.currency, t.institutionTransactionDate,
        t.institutionBusinessDate,
    )

    /** 새 결정의 첫 응답 전송 시나리오(AUTH-001 §5.3 Demo 표). 원장 커밋은 이미 끝났다. */
    private fun transport(frame: VanFrame, payload: ByteArray, behaviour: VanBehaviour, out: OutputStream): Boolean {
        val key = checkNotNull(settings.keyRing.find(frame.keyId)).secret
        return when (behaviour) {
            VanBehaviour.DROP_AFTER_APPROVE -> false
            VanBehaviour.LATE_RESPONSE -> {
                Thread.sleep(settings.lateResponseDelay)
                write(out, frame, payload)
            }
            VanBehaviour.DUPLICATE_RESPONSE -> write(out, frame, payload) && write(out, frame, payload)
            VanBehaviour.BAD_MAC -> {
                out.write(VanFrameCodec.encodeWithBrokenMac(frame.keyId, key, payload))
                out.flush()
                true
            }
            VanBehaviour.FRAGMENTED -> {
                VanFrameCodec.encode(frame.keyId, key, payload).asList().chunked(FRAGMENT_BYTES).forEach { chunk ->
                    out.write(chunk.toByteArray())
                    out.flush()
                    Thread.sleep(settings.fragmentDelay)
                }
                true
            }
            else -> write(out, frame, payload)
        }
    }

    private fun write(out: OutputStream, frame: VanFrame, payload: ByteArray): Boolean {
        out.write(VanFrameCodec.encode(frame.keyId, checkNotNull(settings.keyRing.find(frame.keyId)).secret, payload))
        out.flush()
        return true
    }

    private fun approval(a: VanApproval) = VanMessages.Approval(
        a.approvalNumber, a.providerTransactionId, a.amountMinor, a.currency, a.plan, a.cardType, a.institutionTransactionDate, a.institutionBusinessDate,
    )

    private companion object {
        const val BACKLOG = 50
        const val FRAGMENT_BYTES = 3
        const val STOP_WAIT_SECONDS = 5L
    }
}
