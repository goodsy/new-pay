package com.paycore.simulator.support

import com.paycore.simulator.authentication.application.SecureRandomSimulatorIdGenerator
import com.paycore.simulator.authentication.application.required.SimulatorClock
import com.paycore.simulator.authentication.application.required.SimulatorIdGenerator
import com.paycore.simulator.authentication.domain.ResultRef
import com.paycore.simulator.authentication.domain.TransactionId
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicReference

/** 시험 시계(@Primary): 기본은 [default], 시험이 옮긴 뒤 [reset]. */
class MutableSimulatorClock(private val default: Instant) : SimulatorClock {
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
