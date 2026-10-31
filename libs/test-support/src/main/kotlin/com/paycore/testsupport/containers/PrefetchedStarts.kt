package com.paycore.testsupport.containers

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * 시험 자원(예: MySQL 컨테이너)을 **한 개 앞서** 백그라운드에서 만들고 시작해 두는 공급기(TEST-PERF-001, docs/repository/TEST-PERF-001-GATE-DURATION.md).
 *
 * - [take]는 언제나 새로 만들어([create]) 시작([start])된, 아직 누구에게도 준 적 없는 인스턴스를 돌려준다. 재사용·공유·초기화 생략이 없다 —
 *   바뀌는 것은 시작 **시점**뿐이다(앞 시험이 도는 동안 다음 컨테이너가 기동한다). Testcontainers `withReuse`와 다르다.
 * - 받은 인스턴스의 수명(중지)은 받은 쪽(Spring 컨텍스트의 bean 소멸·spec의 afterSpec)이 정한다. 남은 예비 인스턴스는 [close](JVM 종료 hook)가 중지한다.
 * - 백그라운드 시작이 실패하면 그 실패를 받는 쪽에 그대로 던진다(감추지 않는다). 다음 [take]는 새 인스턴스를 시작한다.
 * - 메모리: 동시에 예비 인스턴스가 [depth]개 더 떠 있다(측정: TECH-BASELINE §7.3).
 */
class PrefetchedStarts<T : Any>(
    private val name: String,
    /** 미리 시작해 두는 예비 인스턴스 수. 예비는 한 thread에서 차례로 시작한다(동시 기동으로 서로 느려지지 않게). */
    private val depth: Int = 1,
    private val create: () -> T,
    private val start: (T) -> Unit,
    private val stop: (T) -> Unit,
    private val describe: (T) -> String = { it.toString() },
) : AutoCloseable {

    init {
        require(depth >= 1) { "depth must be >= 1: $depth" }
    }

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "prefetch-$name").apply { isDaemon = true } }
    private val spares = ArrayDeque<Future<T>>()
    private var closed = false

    private fun startNext(): Future<T> = executor.submit<T> { create().also(start) }

    /** 미리 시작된 새 인스턴스(없으면 지금 시작한 새 인스턴스)를 돌려주고, 예비가 [depth]개가 되도록 다음 시작을 백그라운드로 건다. */
    @Synchronized
    fun take(): T {
        check(!closed) { "PrefetchedStarts($name) is closed" }
        val current = spares.removeFirstOrNull() ?: startNext()
        try {
            val instance = try {
                current.get()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
            println("[PrefetchedStarts:$name] handing out a newly created, already started instance: ${describe(instance)}")
            return instance
        } finally {
            while (spares.size < depth) spares.addLast(startNext())
        }
    }

    /** 아직 아무에게도 주지 않은 예비 인스턴스를 (시작 중이면 끝난 뒤) 모두 중지한다. 받은 쪽 인스턴스는 건드리지 않는다. */
    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        spares.forEach { pending -> runCatching { pending.get() }.onSuccess { runCatching { stop(it) } } }
        spares.clear()
        executor.shutdown()
    }

    companion object {
        /** JVM이 끝날 때 [close]한다(시험 JVM 하나에 공급기 하나 — 모듈의 계정/컨테이너 object에서 쓴다). */
        fun <T : Any> closingAtExit(starts: PrefetchedStarts<T>): PrefetchedStarts<T> =
            starts.also { Runtime.getRuntime().addShutdownHook(Thread(it::close, "prefetch-${it.name}-close")) }
    }
}
