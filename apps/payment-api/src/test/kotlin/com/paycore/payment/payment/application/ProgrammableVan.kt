package com.paycore.payment.payment.application

import com.paycore.payment.payment.adapter.integration.institution.ConfiguredInstitutionRouteRegistry
import com.paycore.payment.payment.adapter.integration.institution.SimulatorVanAuthorizationAdapter
import com.paycore.payment.payment.adapter.integration.institution.SimulatorVanCancellationAdapter
import com.paycore.payment.payment.application.required.CancellationCall
import com.paycore.payment.payment.application.required.CancellationCallResult
import com.paycore.payment.payment.application.required.CancellationPort
import com.paycore.payment.payment.adapter.integration.institution.StubVan
import com.paycore.payment.payment.adapter.integration.institution.StubVan.Action
import com.paycore.payment.payment.application.required.AuthorizationCall
import com.paycore.payment.payment.application.required.AuthorizationCallResult
import com.paycore.payment.payment.application.required.AuthorizationPort
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.springframework.beans.factory.config.BeanPostProcessor
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.DynamicPropertyRegistrar
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.TransactionSystemException
import org.springframework.transaction.support.TransactionSynchronizationManager
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * 시험 전용 stub VAN(AUTH-001 §8 — payment-api 시험은 Simulator 앱에 의존하지 않는다). 실제 소켓의 [StubVan] 위에 **원장 의미를 메모리로 재현**한다: 결정은 요청 ID별로 한 번
 * 기록되고(같은 ID 재전송은 같은 결정), 조회는 기록을 FOUND로·종결 표지를 NO_EFFECT_CONFIRMED로·없으면 NOT_FOUND(closeIfAbsent면 종결 기록)로 답한다. 승인 응답의 전송 동작
 * (응답 없이 끊기·무응답·분할)은 MID별 대본으로 정한다. golden 시험 키를 쓴다. 운영 VAN이 아니다.
 */
class ProgrammableVan : AutoCloseable {
    /** 승인 요청 한 건에 대한 대본: [decision]은 원장에 남길 응답(null = 기록 없음), [transport]는 이번 응답의 전송 동작(null = 정상 응답). */
    data class Script(val decision: ObjectNode?, val transport: Action? = null)

    val authRequests = CopyOnWriteArrayList<JsonNode>()
    val inquiries = CopyOnWriteArrayList<JsonNode>()
    private val ledger = ConcurrentHashMap<String, ObjectNode>() // requestId → AUTH_RESPONSE(결정)
    private val closures = ConcurrentHashMap<String, String>() // requestId → closureId
    private val scripts = ConcurrentHashMap<String, (JsonNode) -> Script>() // MID → 대본
    private val inquiryScripts = ConcurrentHashMap<String, (JsonNode) -> Action?>() // requestId → 조회 대본(null = 원장 의미)
    private val seq = AtomicInteger()

    // CANCEL-001 S6: sim-van-2 취소 원장(취소 요청 ID별 결정 한 번 — 재전송은 같은 결정), 취소 무효과 종결 표지, MID별 취소 대본.
    val cancelRequests = CopyOnWriteArrayList<JsonNode>()
    val cancelInquiries = CopyOnWriteArrayList<JsonNode>()
    private val cancelLedger = ConcurrentHashMap<String, ObjectNode>() // cancel requestId → CANCEL_RESPONSE(결정)
    private val cancelClosures = ConcurrentHashMap<String, String>()
    private val cancelScripts = ConcurrentHashMap<String, (JsonNode) -> Script>()
    private val cancelInquiryScripts = ConcurrentHashMap<String, (JsonNode) -> Action?>()

    private val stub = StubVan { request -> handle(request) }
    val port: Int get() = stub.port

    fun onAuth(mid: String, script: (JsonNode) -> Script) {
        scripts[mid] = script
    }

    fun onCancel(mid: String, script: (JsonNode) -> Script) {
        cancelScripts[mid] = script
    }

    fun onCancelInquiry(cancelRequestId: String, script: (JsonNode) -> Action?) {
        cancelInquiryScripts[cancelRequestId] = script
    }

    /** 취소 원장에 결정을 직접 넣는다(대사·통보를 흉내 내는 시험 절차). */
    fun recordCancel(cancelRequestId: String, decision: ObjectNode) {
        cancelLedger.putIfAbsent(cancelRequestId, decision)
    }

    fun cancelRequestsFor(mid: String): List<JsonNode> = cancelRequests.filter { it.get("merchantRef").stringValue() == mid }

    fun cancelDecided(cancelRequestId: String): ObjectNode? = cancelLedger[cancelRequestId]

    fun cancelInquiriesFor(cancelRequestId: String): List<JsonNode> = cancelInquiries.filter { it.get("originalRequestId").stringValue() == cancelRequestId }

    fun cancelClosureFor(cancelRequestId: String): String? = cancelClosures[cancelRequestId]

    /** 취소 승인(요청 금액·통화·원 PTX echo, 시험마다 새 decisionId·취소 승인번호·취소 PTX). [mutate]로 조건 불일치 승인을 만든다. */
    fun cancelApproved(request: JsonNode, mutate: (ObjectNode) -> Unit = {}): ObjectNode = StubVan.replyV2("cancelResponseApproved", request).also { r ->
        val n = seq.incrementAndGet()
        r.put("decisionId", "vd_" + ulid())
        r.put("cancelApprovalNumber", "4" + "%07d".format(n))
        r.put("providerTransactionId", "SIMPTX-C" + UUID.randomUUID().toString().replace("-", "").take(15))
        r.put("originalProviderTransactionId", request.get("originalProviderTransactionId").stringValue())
        r.put("cancelledAmountMinor", request.get("cancelAmountMinor").longValue())
        r.put("currency", request.get("currency").stringValue())
        mutate(r)
    }

    fun approveCancel(request: JsonNode): Script = Script(cancelApproved(request))

    fun declineCancel(request: JsonNode, category: String = "ISSUER_DECLINED"): Script =
        Script(StubVan.replyV2("cancelResponseDeclined", request).put("decisionId", "vd_" + ulid()).put("declineCategory", category))

    fun pendingCancel(request: JsonNode): Script = Script(StubVan.replyV2("cancelResponsePending", request).put("pendingId", "vp_" + ulid()))

    fun onInquiry(requestId: String, script: (JsonNode) -> Action?) {
        inquiryScripts[requestId] = script
    }

    /** 원장에 승인을 직접 넣는다(대사·통보를 흉내 내는 시험 절차 — §5.3 VC-VAN-SECOND-APPROVAL 대체). */
    fun record(requestId: String, decision: ObjectNode) {
        ledger.putIfAbsent(requestId, decision)
    }

    fun authRequestsFor(mid: String): List<JsonNode> = authRequests.filter { it.get("merchantRef").stringValue() == mid }

    fun decided(requestId: String): ObjectNode? = ledger[requestId]

    /** 원 요청 ID에 대한 조회 요청들(받은 순서). */
    fun inquiriesFor(requestId: String): List<JsonNode> = inquiries.filter { it.get("originalRequestId").stringValue() == requestId }

    /** 원 요청 ID의 무효과 종결 표지(없으면 null). */
    fun closureFor(requestId: String): String? = closures[requestId]

    /** PENDING 예약을 결정으로 확정한다(기관의 PENDING 처리 완료를 흉내 — 원장 PENDING 행만 바꾼다). */
    fun resolvePending(requestId: String, decide: (JsonNode) -> ObjectNode) {
        val request = authRequests.first { it.get("requestId").stringValue() == requestId }
        val current = checkNotNull(ledger[requestId]) { "no ledger entry" }
        check(current.get("outcome").stringValue() == "PENDING") { "only a PENDING entry is resolved" }
        ledger[requestId] = decide(request)
    }

    private fun handle(request: JsonNode): Action = when (request.get("type").stringValue()) {
        "AUTH_REQUEST" -> {
            authRequests.add(request)
            val rid = request.get("requestId").stringValue()
            val existing = ledger[rid]
            when {
                closures.containsKey(rid) -> Action.Respond(StubVan.bytes(StubVan.reply("authResponseRejectedClosed", request)))
                existing != null -> Action.Respond(StubVan.bytes(existing.deepCopy().put("inReplyTo", request.get("messageId").stringValue())))
                else -> {
                    val script = (scripts[request.get("merchantRef").stringValue()] ?: ::approve)(request)
                    script.decision?.let { ledger.putIfAbsent(rid, it) }
                    script.transport ?: Action.Respond(StubVan.bytes(checkNotNull(script.decision) { "no decision and no transport" }))
                }
            }
        }
        "CANCEL_REQUEST" -> {
            cancelRequests.add(request)
            val rid = request.get("requestId").stringValue()
            val existing = cancelLedger[rid]
            when {
                cancelClosures.containsKey(rid) -> Action.Respond(StubVan.bytes(StubVan.replyV2("cancelResponseRejectedClosed", request)))
                existing != null -> Action.Respond(StubVan.bytes(existing.deepCopy().put("inReplyTo", request.get("messageId").stringValue())))
                else -> {
                    val script = (cancelScripts[request.get("merchantRef").stringValue()] ?: ::approveCancel)(request)
                    script.decision?.let { cancelLedger.putIfAbsent(rid, it) }
                    script.transport ?: Action.Respond(StubVan.bytes(checkNotNull(script.decision) { "no decision and no transport" }))
                }
            }
        }
        "CANCEL_INQUIRY_REQUEST" -> {
            cancelInquiries.add(request)
            val rid = request.get("originalRequestId").stringValue()
            cancelInquiryScripts[rid]?.invoke(request) ?: cancelInquiryReply(request, rid)
        }
        else -> {
            inquiries.add(request)
            val rid = request.get("originalRequestId").stringValue()
            inquiryScripts[rid]?.invoke(request) ?: inquiryReply(request, rid)
        }
    }

    private fun inquiryReply(request: JsonNode, rid: String): Action {
        val decision = ledger[rid]
        val reply: ObjectNode = when {
            decision != null && decision.get("outcome").stringValue() == "PENDING" ->
                StubVan.reply("inquiryResponsePending", request).put("pendingId", decision.get("pendingId").stringValue())
            decision != null -> found(request, decision)
            closures.containsKey(rid) -> StubVan.reply("inquiryResponseNoEffectConfirmed", request).put("closureId", closures.getValue(rid))
            request.get("closeIfAbsent").booleanValue() -> {
                val closureId = "vc_" + ulid()
                closures[rid] = closureId
                StubVan.reply("inquiryResponseNoEffectConfirmed", request).put("closureId", closureId)
            }
            else -> StubVan.reply("inquiryResponseNotFound", request)
        }
        return Action.Respond(StubVan.bytes(reply))
    }

    private fun cancelInquiryReply(request: JsonNode, rid: String): Action {
        val decision = cancelLedger[rid]
        val reply: ObjectNode = when {
            decision != null && decision.get("outcome").stringValue() == "PENDING" ->
                StubVan.replyV2("cancelInquiryResponsePending", request).put("pendingId", decision.get("pendingId").stringValue())
            decision != null -> {
                val base = if (decision.get("outcome").stringValue() == "APPROVED") StubVan.goldenV2("cancelInquiryResponseFound") else StubVan.goldenV2("cancelInquiryResponseFoundDeclined")
                val out = StubVan.MAPPER.createObjectNode()
                // 결정 필드는 기록된 결정에서(없는 선택 필드 — 취소 승인번호 등 — 는 생략), 봉투는 조회 요청에서.
                base.properties().forEach { (k, v) ->
                    when {
                        k in setOf("type", "inReplyTo", "inquiryId", "originalRequestId", "result", "respondedAt") -> out.set(k, v)
                        decision.has(k) -> out.set(k, decision.get(k))
                    }
                }
                decision.properties().forEach { (k, v) -> if (k == "cancelApprovalNumber" && !out.has(k)) out.set(k, v) }
                out.put("inReplyTo", request.get("messageId").stringValue()).put("inquiryId", request.get("inquiryId").stringValue())
                    .put("originalRequestId", request.get("originalRequestId").stringValue())
            }
            cancelClosures.containsKey(rid) -> StubVan.replyV2("cancelInquiryResponseNoEffectConfirmed", request).put("closureId", cancelClosures.getValue(rid))
            request.get("closeIfAbsent").booleanValue() -> {
                val closureId = "vc_" + ulid()
                cancelClosures[rid] = closureId
                StubVan.replyV2("cancelInquiryResponseNoEffectConfirmed", request).put("closureId", closureId)
            }
            else -> StubVan.replyV2("cancelInquiryResponseNotFound", request)
        }
        return Action.Respond(StubVan.bytes(reply))
    }

    private fun found(request: JsonNode, decision: ObjectNode): ObjectNode {
        val base = if (decision.get("outcome").stringValue() == "APPROVED") StubVan.golden("inquiryResponseFound") else StubVan.golden("inquiryResponseFoundDeclined")
        val out = StubVan.MAPPER.createObjectNode()
        base.properties().forEach { (k, v) ->
            out.set(k, if (decision.has(k) && k !in setOf("type", "inReplyTo", "respondedAt")) decision.get(k) else v)
        }
        return out.put("inReplyTo", request.get("messageId").stringValue()).put("inquiryId", request.get("inquiryId").stringValue())
            .put("originalRequestId", request.get("originalRequestId").stringValue())
    }

    /** 승인 결정(요청 금액·통화·Plan echo, 시험마다 새 decisionId·승인번호·PTX). [mutate]로 조건 불일치 승인을 만든다. */
    fun approved(request: JsonNode, mutate: (ObjectNode) -> Unit = {}): ObjectNode = StubVan.reply("authResponseApproved", request).also { r ->
        val n = seq.incrementAndGet()
        r.put("decisionId", "vd_" + ulid())
        r.put("approvalNumber", "3" + "%07d".format(n))
        r.put("providerTransactionId", "SIMPTX-" + UUID.randomUUID().toString().replace("-", "").take(16))
        r.put("approvedAmountMinor", request.get("amountMinor").longValue())
        r.put("currency", request.get("currency").stringValue())
        r.set("plan", request.get("plan").deepCopy())
        mutate(r)
    }

    fun approve(request: JsonNode): Script = Script(approved(request))

    fun decline(request: JsonNode, category: String = "ISSUER_DECLINED"): Script =
        Script(StubVan.reply("authResponseDeclined", request).put("decisionId", "vd_" + ulid()).put("declineCategory", category))

    fun pending(request: JsonNode): Script = Script(StubVan.reply("authResponsePending", request).put("pendingId", "vp_" + ulid()))

    private fun ulid(): String = (System.currentTimeMillis().toString(32).uppercase() + UUID.randomUUID().toString().replace("-", "").uppercase())
        .map { c -> if (c in "0123456789ABCDEFGHJKMNPQRSTVWXYZ") c else 'Z' }.joinToString("").padEnd(26, '0').take(26)

    override fun close() = stub.close()
}

/**
 * 시험 전용 commit 장애(검토 #3 m-3, C29·AU-28): [arm] 뒤 처음 오는 **새 읽기·쓰기 TX**의 commit을 실제로 수행한 다음 예외를 던진다 — 호출자는 커밋 여부를 알 수 없다
 * (`LocalTransactions`가 `CommitOutcomeUnknownException`으로 바꾼다). 무장하지 않으면 그대로 위임한다.
 */
object CommitFaults {
    private val armed = AtomicBoolean(false)

    fun arm() = armed.set(true)

    fun reset() {
        armed.set(false)
        beginFailureFor.set(null)
    }

    /**
     * CP-QUERY-001 검토 #1 m-2(E-1): 이름에 [definitionNameContains]가 든 TX 정의(Spring은 `클래스.메서드` 이름을 쓴다)의 **시작**(연결 획득 단계)을 한 번 실패시킨다 — 쿼리 중이 아니라 TX begin 실패.
     */
    fun armBeginFailure(definitionNameContains: String) = beginFailureFor.set(definitionNameContains)

    private val beginFailureFor = java.util.concurrent.atomic.AtomicReference<String?>(null)

    internal fun beforeBegin(definition: TransactionDefinition?) {
        val marker = beginFailureFor.get() ?: return
        if (definition?.name?.contains(marker) == true && beginFailureFor.compareAndSet(marker, null)) {
            throw org.springframework.transaction.CannotCreateTransactionException("injected: could not acquire a connection at transaction begin")
        }
    }

    internal fun afterCommit(status: TransactionStatus) {
        val fresh = status.isNewTransaction && !status.isReadOnly
        if (fresh && armed.compareAndSet(true, false)) throw TransactionSystemException("injected: connection lost after commit (outcome unknown to the caller)")
    }
}

/** [CommitFaults]를 거치는 위임 TM(Bean 이름 `transactionManager` 유지). */
class CommitFaultingTransactionManager(private val delegate: PlatformTransactionManager) : PlatformTransactionManager {
    override fun getTransaction(definition: TransactionDefinition?): TransactionStatus {
        CommitFaults.beforeBegin(definition)
        return delegate.getTransaction(definition)
    }

    override fun commit(status: TransactionStatus) {
        delegate.commit(status)
        CommitFaults.afterCommit(status)
    }

    override fun rollback(status: TransactionStatus) = delegate.rollback(status)
}

/**
 * 운영 [SimulatorVanAuthorizationAdapter]를 감싼 승인 Port(@Primary, 시험 전용 — ArchUnit은 main만 본다): 호출마다 호출 스레드에 실제 DB TX가 있었는지 기록하고(AC 2·검토 #3 n-8),
 * [throwBeforeSend]면 연결 전에 예외를 던진다(검토 #3 n-2 — 전송자 결함).
 */
class ObservedAuthorizationPort(private val real: SimulatorVanAuthorizationAdapter) : AuthorizationPort {
    val transactionActiveAtCall = CopyOnWriteArrayList<Boolean>()

    @Volatile
    var throwBeforeSend: Boolean = false

    override fun authorize(call: AuthorizationCall): AuthorizationCallResult {
        transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive())
        if (throwBeforeSend) throw IllegalStateException("injected sender defect before any byte")
        return real.authorize(call)
    }
}

/**
 * 운영 [SimulatorVanCancellationAdapter]를 감싼 취소 Port(@Primary, 시험 전용 — CANCEL-001 S6): 호출마다 호출 스레드에 실제 DB TX가 있었는지 기록하고(K-13 — 외부 호출은 TX 밖),
 * [throwOnCall]이면 연결 전에 예외를 던진다(검토 #2 n-4 — 전송자 결함은 OutcomeUnknown으로 반영돼야 한다).
 */
class ObservedCancellationPort(private val real: SimulatorVanCancellationAdapter) : CancellationPort {
    val transactionActiveAtCall = CopyOnWriteArrayList<Boolean>()

    @Volatile
    var throwOnCall: Boolean = false

    override fun cancel(call: CancellationCall): CancellationCallResult {
        transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive())
        if (throwOnCall) throw IllegalStateException("injected sender defect")
        return real.cancel(call)
    }
}

/** 운영 [SimulatorVanCancellationAdapter]를 감싼 취소 조회 Port(@Primary, 시험 전용 — 검토 #3 n-4): 호출 스레드에 DB TX가 있었는지 기록한다(회복 조회는 TX 밖). */
class ObservedCancelInquiryPort(private val real: SimulatorVanCancellationAdapter) : com.paycore.payment.payment.application.required.CancelInquiryPort {
    val transactionActiveAtCall = CopyOnWriteArrayList<Boolean>()

    override fun inquire(call: com.paycore.payment.payment.application.required.CancelInquiryCall): com.paycore.payment.payment.application.required.CancelInquiryCallResult {
        transactionActiveAtCall.add(TransactionSynchronizationManager.isActualTransactionActive())
        return real.inquire(call)
    }
}

@TestConfiguration(proxyBeanMethods = false)
class ProgrammableVanTestConfig {
    @Bean
    @Primary
    fun observedCancelInquiryPort(real: SimulatorVanCancellationAdapter): ObservedCancelInquiryPort = ObservedCancelInquiryPort(real)

    @Bean
    @Primary
    fun observedCancellationPort(real: SimulatorVanCancellationAdapter): ObservedCancellationPort = ObservedCancellationPort(real)

    @Bean
    @Primary
    fun observedAuthorizationPort(real: SimulatorVanAuthorizationAdapter): ObservedAuthorizationPort = ObservedAuthorizationPort(real)

    @Bean
    @Primary
    fun withdrawableRouteResolver(real: ConfiguredInstitutionRouteRegistry): WithdrawableRouteResolver = WithdrawableRouteResolver(real)

    companion object {
        /** `transactionManager`를 [CommitFaultingTransactionManager]로 감싼다(static — 다른 Bean보다 먼저 등록). */
        @JvmStatic
        @Bean
        fun commitFaultingTransactionManager(): BeanPostProcessor = object : BeanPostProcessor {
            override fun postProcessAfterInitialization(bean: Any, beanName: String): Any =
                if (beanName == "transactionManager" && bean is PlatformTransactionManager) CommitFaultingTransactionManager(bean) else bean
        }
    }

    @Bean(destroyMethod = "close")
    fun programmableVan(): ProgrammableVan = ProgrammableVan()

    /** 시험 Route(CANCEL-001: `SIMVANC`·`SIMVANCB` sim-van-2 포함) `SIMVAN01`(프로파일 A)·`SIMVANB`(프로파일 B — 무효과 확정 없음)가 이 stub의 포트를 가리키게 한다(운영 설정에는 포트 기본값 없음). */
    @Bean
    fun programmableVanRoute(van: ProgrammableVan): DynamicPropertyRegistrar = DynamicPropertyRegistrar { registry ->
        registry.add("paycore.authorization.routes.SIMVAN01.port") { van.port }
        registry.add("paycore.authorization.routes.SIMVANB.port") { van.port }
        registry.add("paycore.authorization.routes.SIMVANC.port") { van.port }
        registry.add("paycore.authorization.routes.SIMVANCB.port") { van.port }
    }
}
