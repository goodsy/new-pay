package com.paycore.admin.account.adapter.batch

import com.paycore.admin.InternalAdminApplication
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource

/**
 * 1회성 명령 모드 가드 — 웹 쪽(§4.9, ADMIN-BOOT-002 최종 리뷰 Major-1 — Coordinator 결정 fail-closed): 웹 애플리케이션 컨텍스트에 명령 속성
 * `paycore.admin.command`가 **어떤 경로로든**(인자·속성·환경변수 `PAYCORE_ADMIN_COMMAND`) 들어오면 기동 자체를 실패시킨다.
 * 이전 기대(웹 컨텍스트가 뜬 채 runner만 MODE_REFUSED 경고)는 서비스 중인 ADMIN이 경고 한 줄만 남기고 계속 돌거나, 주기 job이 조용히 지워질 수 있어 바뀌었다.
 * 실패 시점은 빈 정의 후처리 단계(웹 서버·DB 연결 전)라 DB 없이 실제 서블릿 웹 구성으로 띄운다. 다른 이유의 기동 실패와 구별하려고 고정 code를 단언한다.
 */
class OneShotModeWebSpec : FunSpec({

    fun startServletWeb(environment: StandardEnvironment? = null, properties: List<String> = emptyList(), args: List<String> = emptyList()): Throwable? = runCatching {
        SpringApplicationBuilder(InternalAdminApplication::class.java).web(WebApplicationType.SERVLET).logStartupInfo(false).registerShutdownHook(false)
            .properties("server.port=0", *properties.toTypedArray())
            .also { builder -> environment?.let { builder.environment(it) } }
            .run(*args.toTypedArray()).close()
    }.exceptionOrNull()

    fun refusedForCommandInWeb(failure: Throwable?): Boolean =
        generateSequence(failure) { it.cause }.any { it is IllegalStateException && it.message.orEmpty().contains(OneShotModePeriodicJobGuard.WEB_CONTEXT_REFUSAL) }

    test("command property from an environment variable (PAYCORE_ADMIN_COMMAND) in a servlet web context → startup fails (fail-closed)") {
        val environment = StandardEnvironment().also {
            it.propertySources.addFirst(SystemEnvironmentPropertySource("paycore-test-env", mapOf<String, Any>("PAYCORE_ADMIN_COMMAND" to "bootstrap")))
        }
        refusedForCommandInWeb(startServletWeb(environment = environment)) shouldBe true
    }

    test("command property from a configuration property (no arguments) in a servlet web context → startup fails") {
        refusedForCommandInWeb(startServletWeb(properties = listOf("paycore.admin.command=bootstrap"))) shouldBe true
    }

    test("command argument in a servlet web context → startup fails (was: web context up with runner MODE_REFUSED)") {
        refusedForCommandInWeb(startServletWeb(args = listOf("--paycore.admin.command=bootstrap", "--output=tty"))) shouldBe true
    }
})
