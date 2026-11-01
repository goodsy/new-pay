package com.paycore.master.merchant.adapter.batch

import com.paycore.master.MasterApiApplication
import com.paycore.master.runCommand
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.env.SystemEnvironmentPropertySource
import java.nio.file.Files

/**
 * DEMO 배포 모드 게이트(DEMO-SEED-001 명세 §4.6, I-1·I-2 — DB 없이). DataSource URL을 **도달 불가**(`127.0.0.1:1`)로 둔다: 게이트가 컨텍스트 생성 전에
 * 거부하면 exit 3, 게이트가 늦으면(runner 단계) Flyway 연결 실패로 exit 1이 된다(변이 S11의 판별). 웹 컨텍스트 가드는 빈 정의 단계라 DB에 닿지 않는다.
 */
class DemoSeedModeGateSpec : FunSpec({

    val unreachable = arrayOf(
        "--PAYCORE_MASTER_DB_URL=jdbc:mysql://127.0.0.1:1/none?connectTimeout=500",
        "--PAYCORE_MASTER_DB_USERNAME=nobody", "--PAYCORE_MASTER_DB_PASSWORD=none",
        "--PAYCORE_MASTER_DB_MIGRATION_USERNAME=nobody", "--PAYCORE_MASTER_DB_MIGRATION_PASSWORD=none",
        "--spring.datasource.hikari.initialization-fail-timeout=1",
        "--output=file:/nonexistent/x.tsv", "--effective-from=2026-10-10T00:00:00+09:00",
    )

    fun run(vararg extra: String): Int = runCommand(arrayOf("--paycore.master.command=demo-seed", *extra) + unreachable)

    listOf(
        "LIVE" to arrayOf("--paycore.master.deployment-mode=LIVE"),
        "absent" to emptyArray(),
        "empty" to arrayOf("--paycore.master.deployment-mode="),
        "lower-case demo" to arrayOf("--paycore.master.deployment-mode=demo"),
        "TEST" to arrayOf("--paycore.master.deployment-mode=TEST"),
        "padded" to arrayOf("--paycore.master.deployment-mode= DEMO"),
    ).forEach { (name, args) ->
        test("deployment mode $name → exit 3 before any DB connection") { run(*args) shouldBe 3 }
    }

    test("LIVE given only in a config file (spring.config.additional-location) → exit 3") {
        val file = Files.createTempFile("demo-gate-", ".properties")
        Files.writeString(file, "paycore.master.deployment-mode=LIVE\n")
        run("--spring.config.additional-location=file:$file") shouldBe 3
    }

    test("DEMO passes the gate and then fails on the unreachable database (exit 1, not 3)") {
        run("--paycore.master.deployment-mode=DEMO") shouldNotBe 3
    }

    test("the gate is ordered after config-file loading") {
        DemoCommandModeGate().order shouldBe org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1
    }

    fun webFailure(builder: SpringApplicationBuilder): Throwable = shouldThrow<Throwable> { builder.web(WebApplicationType.SERVLET).run(*unreachable) }

    fun chain(t: Throwable): String = generateSequence(t) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    test("command property + DEMO in a web context fails start-up with the fixed code — argument path (I-2)") {
        val failure = webFailure(SpringApplicationBuilder(MasterApiApplication::class.java).properties("server.port=0"))
        // 인자 없이 시작한 웹은 정상 경로라 위 호출은 DB에서 실패한다 — 명령 인자를 준 경우만 고정 code가 나와야 한다.
        chain(failure).contains(OneShotModeGuard.WEB_CONTEXT_REFUSAL) shouldBe false
        val withCommand = shouldThrow<Throwable> {
            SpringApplicationBuilder(MasterApiApplication::class.java).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--paycore.master.command=demo-seed", "--paycore.master.deployment-mode=DEMO", *unreachable)
        }
        chain(withCommand) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }

    test("command property + DEMO in a web context fails start-up — configuration-property path") {
        val failure = webFailure(
            SpringApplicationBuilder(MasterApiApplication::class.java).properties("server.port=0", "paycore.master.command=demo-seed", "paycore.master.deployment-mode=DEMO"),
        )
        chain(failure) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }

    test("command property + DEMO in a web context fails start-up — environment-variable path") {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                SystemEnvironmentPropertySource("test-env", mapOf<String, Any>("PAYCORE_MASTER_COMMAND" to "demo-seed", "PAYCORE_MASTER_DEPLOYMENT_MODE" to "DEMO")),
            )
        }
        val failure = webFailure(SpringApplicationBuilder(MasterApiApplication::class.java).environment(environment).properties("server.port=0"))
        chain(failure) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }
})
