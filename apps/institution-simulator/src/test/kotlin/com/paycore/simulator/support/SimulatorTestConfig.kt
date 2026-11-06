package com.paycore.simulator.support

import com.paycore.simulator.acquirer.application.required.AcquirerClock
import com.paycore.simulator.authentication.application.SecureRandomSimulatorIdGenerator
import com.paycore.simulator.authentication.application.required.SimulatorClock
import com.paycore.simulator.authentication.application.required.SimulatorIdGenerator
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId
import com.paycore.simulator.merchant.application.required.MerchantReceiverClock
import com.paycore.simulator.van.application.required.VanClock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/**
 * 시험 시계(@Primary): 기본은 [default], 시험이 옮긴 뒤 [reset]. 인증·가상 VAN이 같은 시각원을 쓴다(증거 유효기간 재검증 — AUTH-001 D-AUTH1-004).
 * 가상 가맹점 수신기(WEBHOOK-001 §9)도 같은 시계로 서명 재전송 창을 판정한다. 가상 매입사(ACQUIRING-001 2-E)도 같은 시계로 수신·게시 시각을 정한다(가상 시계).
 */
class MutableSimulatorClock(private val default: Instant) : SimulatorClock, VanClock, MerchantReceiverClock, AcquirerClock {
    private val now = AtomicReference(default)

    override fun now(): Instant = now.get()

    fun set(at: Instant) = now.set(at)

    fun reset() = now.set(default)
}

/** 시험 식별자(@Primary): [enqueue]한 값을 먼저 내고, 없으면 운영 생성기(golden 재현용). */
class ScriptedSimulatorIdGenerator(private val real: SimulatorIdGenerator) : SimulatorIdGenerator {
    private val transactions = ConcurrentLinkedQueue<TransactionId>()
    private val results = ConcurrentLinkedQueue<ResultRef>()
    private val evidences = ConcurrentLinkedQueue<String>()

    fun enqueue(transactionId: String, resultRef: String, evidenceRef: String) {
        transactions += TransactionId(transactionId)
        results += ResultRef(resultRef)
        evidences += evidenceRef
    }

    override fun transactionId(): TransactionId = transactions.poll() ?: real.transactionId()

    override fun resultRef(): ResultRef = results.poll() ?: real.resultRef()

    override fun evidenceRef(): String = evidences.poll() ?: real.evidenceRef()
}

@TestConfiguration(proxyBeanMethods = false)
class SimulatorTestConfig {
    @Bean
    @Primary
    fun mutableSimulatorClock(): MutableSimulatorClock = MutableSimulatorClock(Instant.parse("2026-10-16T01:00:00.000Z"))

    @Bean
    @Primary
    fun scriptedSimulatorIdGenerator(): ScriptedSimulatorIdGenerator = ScriptedSimulatorIdGenerator(SecureRandomSimulatorIdGenerator())
}
