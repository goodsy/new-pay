package com.paycore.simulator.acquirer.adapter.integration

import com.paycore.simulator.acquirer.application.provided.RunAcquirerWorkerUseCase
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * 가상 매입사 Worker 주기 실행(ACQUIRING-001 2-E — Demo). `poll-interval`마다 [RunAcquirerWorkerUseCase.runOnce]를 단일 스레드로 부른다(첫 실행도 한 주기 뒤).
 * 매입사가 설정되지 않으면 시작하지 않는다. 회차 실패는 error 로그만 남기고 다음 회차에 다시 한다(수신·게시는 멱등 — 기록된 계획만 게시).
 */
@Component
class AcquirerWorkerScheduler(private val settings: AcquirerSettings, private val worker: RunAcquirerWorkerUseCase) : SmartLifecycle {

    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var executor: ScheduledExecutorService? = null

    override fun start() {
        if (!settings.configured) return
        val interval = settings.pollInterval.toMillis()
        executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "acquirer-worker").apply { isDaemon = true } }.also { e ->
            e.scheduleWithFixedDelay(::runSafely, interval, interval, TimeUnit.MILLISECONDS)
        }
    }

    private fun runSafely() {
        try {
            worker.runOnce()
        } catch (e: Exception) {
            log.error("virtual acquirer worker round failed ({}); retried next round", e.javaClass.simpleName)
        }
    }

    override fun stop() {
        executor?.let { e ->
            e.shutdownNow()
            e.awaitTermination(STOP_WAIT_SECONDS, TimeUnit.SECONDS)
        }
        executor = null
    }

    override fun isRunning(): Boolean = executor != null

    private companion object {
        const val STOP_WAIT_SECONDS = 10L
    }
}
