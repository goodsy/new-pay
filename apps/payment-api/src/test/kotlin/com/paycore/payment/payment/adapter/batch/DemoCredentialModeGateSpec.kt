package com.paycore.payment.payment.adapter.batch

import com.paycore.payment.PaymentApiApplication
import com.paycore.payment.effectiveArguments
import com.paycore.payment.exitCodeOf
import com.paycore.payment.runCommand
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
 * payment-api DEMO 배포 모드 게이트(DEMO-SEED-001 명세 §4.6, I-16 첫 줄·I-2·I-3 진입점 — DB 없이). DataSource URL을 **도달 불가**(`127.0.0.1:1`)로 둔다: 게이트가
 * 컨텍스트 생성 전에 거부하면 exit 3, 게이트가 늦으면(runner 단계) Flyway 연결 실패로 exit 1이 된다(변이 S11의 판별). 웹 컨텍스트 가드는 빈 정의 단계라 DB에 닿지 않는다.
 */
class DemoCredentialModeGateSpec : FunSpec({

    val unreachable = arrayOf(
        "--PAYCORE_PAYMENT_DB_URL=jdbc:mysql://127.0.0.1:1/none?connectTimeout=500",
        "--PAYCORE_PAYMENT_DB_USERNAME=nobody", "--PAYCORE_PAYMENT_DB_PASSWORD=none",
        "--PAYCORE_PAYMENT_DB_MIGRATION_USERNAME=nobody", "--PAYCORE_PAYMENT_DB_MIGRATION_PASSWORD=none",
        "--spring.datasource.hikari.initialization-fail-timeout=1",
        "--output=file:/nonexistent/x.tsv", "--merchant-account-id=mid_01J9ZQG1DENA10000000000000",
    )

    fun run(vararg extra: String): Int = runCommand(arrayOf("--paycore.payment.command=demo-credential", *extra) + unreachable)

    listOf(
        "LIVE" to arrayOf("--paycore.payment.deployment-mode=LIVE"),
        "absent" to emptyArray(),
        "empty" to arrayOf("--paycore.payment.deployment-mode="),
        "lower-case demo" to arrayOf("--paycore.payment.deployment-mode=demo"),
        "TEST" to arrayOf("--paycore.payment.deployment-mode=TEST"),
        "padded" to arrayOf("--paycore.payment.deployment-mode= DEMO"),
    ).forEach { (name, args) ->
        test("deployment mode $name → exit 3 before any DB connection") { run(*args) shouldBe 3 }
    }

    test("LIVE given only in a config file (spring.config.additional-location) → exit 3") {
        val file = Files.createTempFile("demo-credential-gate-", ".properties")
        Files.writeString(file, "paycore.payment.deployment-mode=LIVE\n")
        run("--spring.config.additional-location=file:$file") shouldBe 3
    }

    test("DEMO passes the gate and then fails on the unreachable database (not 3)") {
        run("--paycore.payment.deployment-mode=DEMO") shouldNotBe 3
    }

    test("the gate is ordered after config-file loading") {
        DemoCommandModeGate().order shouldBe org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor.ORDER + 1
    }

    test("the entry point prepends non-web and the five periodic-job-off flags in command mode only (I-3, m3)") {
        effectiveArguments(arrayOf("--paycore.payment.command=demo-credential")).toList() shouldBe listOf(
            "--spring.main.web-application-type=none",
            "--paycore.authorization.timing.recovery.enabled=false",
            "--paycore.cancellation.timing.recovery.enabled=false",
            "--paycore.webhook.fanout.enabled=false",
            "--paycore.webhook.delivery.job.enabled=false",
            "--paycore.payment.checkout-expiry.job.enabled=false",
            "--paycore.payment.command=demo-credential",
        )
        effectiveArguments(arrayOf("--server.port=0")).toList() shouldBe listOf("--server.port=0")
        exitCodeOf(IllegalStateException(DemoModeRefusedException())) shouldBe 3
        exitCodeOf(IllegalStateException("x")) shouldNotBe 3
    }

    fun webFailure(builder: SpringApplicationBuilder): Throwable = shouldThrow<Throwable> { builder.web(WebApplicationType.SERVLET).run(*unreachable) }

    fun chain(t: Throwable): String = generateSequence(t) { it.cause }.joinToString(" | ") { it.message.orEmpty() }

    test("command property + DEMO in a web context fails start-up with the fixed code — argument path (I-2)") {
        val plain = webFailure(SpringApplicationBuilder(PaymentApiApplication::class.java).properties("server.port=0"))
        chain(plain).contains(OneShotModeGuard.WEB_CONTEXT_REFUSAL) shouldBe false
        val withCommand = shouldThrow<Throwable> {
            SpringApplicationBuilder(PaymentApiApplication::class.java).web(WebApplicationType.SERVLET)
                .run("--server.port=0", "--paycore.payment.command=demo-credential", "--paycore.payment.deployment-mode=DEMO", *unreachable)
        }
        chain(withCommand) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }

    test("command property + DEMO in a web context fails start-up — configuration-property path") {
        val failure = webFailure(
            SpringApplicationBuilder(PaymentApiApplication::class.java)
                .properties("server.port=0", "paycore.payment.command=demo-credential", "paycore.payment.deployment-mode=DEMO"),
        )
        chain(failure) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }

    test("command property + DEMO in a web context fails start-up — environment-variable path") {
        val environment = StandardEnvironment().apply {
            propertySources.addFirst(
                SystemEnvironmentPropertySource(
                    "test-env", mapOf<String, Any>("PAYCORE_PAYMENT_COMMAND" to "demo-credential", "PAYCORE_PAYMENT_DEPLOYMENT_MODE" to "DEMO"),
                ),
            )
        }
        val failure = webFailure(SpringApplicationBuilder(PaymentApiApplication::class.java).environment(environment).properties("server.port=0"))
        chain(failure) shouldContain OneShotModeGuard.WEB_CONTEXT_REFUSAL
    }
})
