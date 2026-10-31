package com.paycore.testsupport.containers

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TEST-PERF-001: 미리 시작(prefetch)은 시작 시점만 앞당긴다. 받는 쪽은 언제나 **새로 만들어 시작된, 아무도 받은 적 없는** 인스턴스를 받는다
 * (컨테이너 재사용 아님 — 매 요청 새 컨테이너 결정 유지). Docker 없이 가짜 자원으로 계약만 확인한다.
 */
class PrefetchedStartsSpec : FunSpec({

    class Fake(val id: Int) {
        @Volatile var started = false
        @Volatile var stopped = false
    }

    test("every take returns a distinct, already started instance that no one received before") {
        val created = AtomicInteger()
        val starts = PrefetchedStarts(name = "fake", create = { Fake(created.incrementAndGet()) }, start = { it.started = true }, stop = { it.stopped = true })
        val taken = (1..5).map { starts.take() }
        taken.map { it.id }.toSet().size shouldBe 5
        taken.all { it.started } shouldBe true
        taken.none { it.stopped } shouldBe true
        starts.close()
    }

    test("after a take the next instance is already being started in the background (start overlaps the caller's work)") {
        val startedIds = Collections.synchronizedList(mutableListOf<Int>())
        val secondStarted = CountDownLatch(1)
        val created = AtomicInteger()
        val starts = PrefetchedStarts(name = "fake", create = { Fake(created.incrementAndGet()) }, start = {
            it.started = true; startedIds += it.id; if (it.id == 2) secondStarted.countDown()
        }, stop = { it.stopped = true })
        starts.take().id shouldBe 1
        // 두 번째 take 전에 두 번째 인스턴스의 시작이 이미 끝난다(호출자는 기다리지 않는다).
        secondStarted.await(5, TimeUnit.SECONDS) shouldBe true
        starts.take().id shouldBe 2
        starts.close()
    }

    test("with depth 2 the next two instances are started ahead, one after another, and still each is handed out once") {
        val created = AtomicInteger()
        val thirdStarted = CountDownLatch(1)
        val starts = PrefetchedStarts(name = "fake", depth = 2, create = { Fake(created.incrementAndGet()) }, start = {
            it.started = true; if (it.id == 3) thirdStarted.countDown()
        }, stop = { it.stopped = true })
        starts.take().id shouldBe 1
        thirdStarted.await(5, TimeUnit.SECONDS) shouldBe true // 2와 3이 받기 전에 이미 시작됐다
        created.get() shouldBe 3
        (2..6).map { starts.take().id } shouldBe listOf(2, 3, 4, 5, 6)
        starts.close()
    }

    test("depth must be at least 1") {
        shouldThrow<IllegalArgumentException> { PrefetchedStarts(name = "fake", depth = 0, create = { Fake(0) }, start = {}, stop = {}) }
    }

    test("a failed background start is reported to the taker, and the next take starts a fresh instance") {
        val created = AtomicInteger()
        val starts = PrefetchedStarts(name = "fake", create = { Fake(created.incrementAndGet()) }, start = {
            if (it.id == 2) throw IllegalStateException("docker down for #2")
            it.started = true
        }, stop = { it.stopped = true })
        starts.take().id shouldBe 1
        shouldThrow<IllegalStateException> { starts.take() }.message shouldBe "docker down for #2"
        val third = starts.take()
        third.id shouldBe 3
        third.started shouldBe true
        starts.close()
    }

    test("close stops every unused prefetched instance (waiting for ones still starting) and never hands them out") {
        val all = Collections.synchronizedList(mutableListOf<Fake>())
        val starts = PrefetchedStarts(name = "fake", depth = 2, create = { Fake(all.size + 1).also { f -> all += f } }, start = { it.started = true }, stop = { it.stopped = true })
        val first = starts.take()
        starts.close()
        first.stopped shouldBe false // 받은 쪽 인스턴스의 수명은 받은 쪽(Spring 컨텍스트·spec)이 정한다
        all.size shouldBe 3
        all.filter { it !== first }.all { it.started && it.stopped } shouldBe true
        shouldThrow<IllegalStateException> { starts.take() }
    }
})
