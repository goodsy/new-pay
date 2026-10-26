package com.paycore.admin

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/** 진입점 인자 계산(S6 리뷰 nit-1 — 순수 함수): 명령 모드면 비웹·스케줄러 끔을 **앞에** 덧붙이고, 아니면 그대로. */
class OneShotArgumentsSpec : FunSpec({
    test("command mode prepends non-web and scheduler-off; other runs are untouched") {
        effectiveArguments(arrayOf("--paycore.admin.command=bootstrap", "--output=tty")).toList() shouldBe listOf(
            "--spring.main.web-application-type=none", "--paycore.admin.approval.expiry-job.enabled=false", "--paycore.admin.command=bootstrap", "--output=tty",
        )
        effectiveArguments(arrayOf("--server.port=8443")).toList() shouldBe listOf("--server.port=8443")
        isCommandMode(arrayOf("--paycore.admin.command=recovery")) shouldBe true
        isCommandMode(arrayOf("--paycore.admin.commander=x")) shouldBe false
    }
})
