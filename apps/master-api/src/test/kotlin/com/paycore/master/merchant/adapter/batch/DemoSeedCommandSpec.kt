package com.paycore.master.merchant.adapter.batch

import com.paycore.master.MasterApiApplication
import com.paycore.master.MasterDbAccounts
import com.paycore.master.MasterDbTest
import com.paycore.master.merchant.application.MutableMasterClock
import com.paycore.master.effectiveArguments
import com.paycore.master.exitCodeOf
import com.paycore.master.runCommand
import com.paycore.master.scalar
import com.paycore.testconfig.demoseed.DemoSeedBeansTestConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import org.springframework.boot.WebApplicationType
import org.springframework.boot.builder.SpringApplicationBuilder
import org.springframework.context.annotation.Import
import org.springframework.scheduling.config.ScheduledTaskHolder
import org.testcontainers.mysql.MySQLContainer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * `demo-seed` 명령 adapter(DEMO-SEED-001 명세 §4.6~§4.8, I-1·I-3·I-15)를 실제 MySQL 8.4.11과 실제 argv로 확인한다. 운영 명령은 고정 키 공간
 * `demo-seed-v1`(1회성)이라 이 시험 컨텍스트 DB에서 한 번만 새로 seed되고 이후 실행은 재생이다 — Given 순서가 의미를 갖는다.
 * 시계는 [MutableMasterClock] 기본 2026-10-09T02:00Z → 다음 Seoul 자정 = 2026-10-10T00:00+09:00.
 */
@MasterDbTest
@Import(DemoSeedBeansTestConfig::class)
class DemoSeedCommandSpec(
    private val commandLine: DemoSeedCommandLine,
    private val clock: MutableMasterClock,
    private val container: MySQLContainer,
) : BehaviorSpec({

    // 공유 시계: 컨테이너 본문 실패에도 되돌린다(afterAny — 시험 종류 무관) + Spec 시작·끝(연쇄 실패 차단).
    beforeSpec { clock.reset() }
    afterAny { clock.reset() }
    afterSpec { clock.reset() }

    val t0 = "2026-10-10T00:00:00+09:00"

    fun privateDir(): Path = Files.createTempDirectory("demo-seed-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))

    fun seedRows(): Long = MasterDbAccounts.root(container).use { c ->
        (c.scalar("SELECT COUNT(*) FROM paycore_master.admin_request WHERE idempotency_key LIKE 'demo-seed-v1:%'") as Number).toLong()
    }

    fun dataLines(file: Path): List<Pair<String, String>> =
        Files.readAllLines(file).filter { !it.startsWith("#") }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    Given("refusals that must happen before any DB change") {
        val before = seedRows()
        val dir = privateDir()
        val existing = Files.createFile(dir.resolve("exists.tsv"))
        val shared = Files.createTempDirectory("demo-seed-shared-").also { Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwxrwxrwx")) }

        Then("a T0 that is not a Seoul midnight is USAGE (2)") {
            commandLine.run("demo-seed", mapOf("output" to "file:${dir.resolve("a.tsv")}", "effective-from" to "2026-10-10T01:00:00+09:00")) shouldBe 2
            Files.exists(dir.resolve("a.tsv")) shouldBe false
        }
        Then("an unknown command name is USAGE (2)") {
            commandLine.run("demo-seeds", mapOf("output" to "file:${dir.resolve("b.tsv")}", "effective-from" to t0)) shouldBe 2
        }
        Then("an existing output file, a shared directory and a missing terminal are OUTPUT_REFUSED (6)") {
            commandLine.run("demo-seed", mapOf("output" to "file:$existing", "effective-from" to t0)) shouldBe 6
            commandLine.run("demo-seed", mapOf("output" to "file:${shared.resolve("c.tsv")}", "effective-from" to t0)) shouldBe 6
            commandLine.run("demo-seed", mapOf("output" to "tty", "effective-from" to t0)) shouldBe 6
            Files.exists(shared.resolve("c.tsv")) shouldBe false
        }
        Then("no seed key was recorded") { seedRows() shouldBe before }
    }

    Given("the first production seed in this database") {
        val dir = privateDir()
        val out = dir.resolve("demo-merchant.tsv")
        val code = commandLine.run("demo-seed", mapOf("output" to "file:$out", "effective-from" to t0))

        Then("exit 0 and a 0600 file with the six reference lines in a fixed order (I-15)") {
            code shouldBe 0
            PosixFilePermissions.toString(Files.getPosixFilePermissions(out)) shouldBe "rw-------"
            Files.readAllLines(out).first() shouldStartWith "# PayCore DEMO merchant seed — NOT OPERATIONAL VALUES"
            val lines = dataLines(out)
            lines.map { it.first } shouldBe listOf("merchantId", "merchantAccountId", "siteId", "siteHost", "effectiveFrom", "replayed")
            lines.toMap()["siteHost"] shouldBe "demo-shop.paycore.test"
            lines.toMap()["effectiveFrom"] shouldBe "2026-10-09T15:00:00Z"
            lines.toMap()["replayed"] shouldBe "false"
            seedRows() shouldBe 18
        }

        When("the same command runs again") {
            val again = dir.resolve("again.tsv")
            val second = commandLine.run("demo-seed", mapOf("output" to "file:$again", "effective-from" to t0))
            Then("exit 0, same references, replayed true, no new seed key") {
                second shouldBe 0
                dataLines(again).toMap()["merchantAccountId"] shouldBe dataLines(out).toMap()["merchantAccountId"]
                dataLines(again).toMap()["replayed"] shouldBe "true"
                seedRows() shouldBe 18
            }
        }

        When("a different T0 is given") {
            val other = dir.resolve("other.tsv")
            val third = commandLine.run("demo-seed", mapOf("output" to "file:$other", "effective-from" to "2026-10-11T00:00:00+09:00"))
            Then("CONTENT_CONFLICT (9), nothing recorded and the prepared file removed") {
                third shouldBe 9
                Files.exists(other) shouldBe false
                seedRows() shouldBe 18
            }
        }
    }

    Given("a file output whose write fails after commit (m4)") {
        val dir = privateDir()
        val path = Files.createFile(dir.resolve("broken.tsv"), PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)
        val key = Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java).fileKey()
        channel.close()
        val output = DemoSeedOutput.File(path, channel, key)
        Then("write throws and the file it created is deleted") {
            runCatching { output.write("h", listOf("k" to "v")) }.isFailure shouldBe true
            Files.exists(path) shouldBe false
        }
    }

    Given("a LIVE command against an empty schema (I-1 — the gate runs before any DB access)") {
        MasterDbAccounts.root(container).use { it.createStatement().execute("CREATE DATABASE IF NOT EXISTS demo_gate_probe") }
        val url = MasterDbAccounts.urlFor(container, "demo_gate_probe")
        val code = runCommand(
            arrayOf(
                "--paycore.master.command=demo-seed", "--paycore.master.deployment-mode=LIVE",
                "--PAYCORE_MASTER_DB_URL=$url", "--PAYCORE_MASTER_DB_USERNAME=root", "--PAYCORE_MASTER_DB_PASSWORD=${container.password}",
                "--PAYCORE_MASTER_DB_MIGRATION_USERNAME=root", "--PAYCORE_MASTER_DB_MIGRATION_PASSWORD=${container.password}",
                "--output=file:/nonexistent/x.tsv", "--effective-from=$t0",
            ),
        )
        Then("exit 3 and the schema is still empty (no flyway_schema_history)") {
            code shouldBe 3
            MasterDbAccounts.root(container).use { c ->
                (c.scalar("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = 'demo_gate_probe'") as Number).toLong() shouldBe 0L
            }
        }
    }

    Given("a non-web command context with the entitlement jobs switched on (I-3)") {
        val context = SpringApplicationBuilder(MasterApiApplication::class.java)
            .web(WebApplicationType.NONE)
            .properties(
                "PAYCORE_MASTER_DB_URL=" + MasterDbAccounts.urlFor(container, MasterDbAccounts.MASTER_DATABASE),
                "PAYCORE_MASTER_DB_USERNAME=" + MasterDbAccounts.MASTER_RUNTIME_USER,
                "PAYCORE_MASTER_DB_PASSWORD=" + MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_RUNTIME_USER),
                "PAYCORE_MASTER_DB_MIGRATION_USERNAME=" + MasterDbAccounts.MASTER_MIGRATION_USER,
                "PAYCORE_MASTER_DB_MIGRATION_PASSWORD=" + MasterDbAccounts.passwords.getValue(MasterDbAccounts.MASTER_MIGRATION_USER),
                "spring.flyway.locations=${MasterDbAccounts.MAIN_MIGRATIONS},${MasterDbAccounts.RUNTIME_PRIVILEGES}," +
                    "${MasterDbAccounts.TEST_MIGRATIONS},${MasterDbAccounts.TEST_PRIVILEGES}",
                "paycore.master.command=demo-seed",
                "paycore.master.deployment-mode=DEMO",
                "paycore.master.entitlement.scheduling-enabled=true",
            )
            .run()
        try {
            Then("no scheduled bean and no scheduled task exist; the runner refused the missing output as USAGE") {
                context.getBeanNamesForType(com.paycore.master.merchant.adapter.batch.EntitlementSchedules::class.java).toList().shouldBeEmpty()
                context.getBeansOfType(ScheduledTaskHolder::class.java).values.flatMap { it.scheduledTasks }.shouldBeEmpty()
                context.getBean(DemoSeedCommandRunner::class.java).exitCode shouldBe 2
            }
            Then("the production wiring alone provides exactly one seed use case (registered by the command guard) and one command line (review m-2)") {
                context.getBeanNamesForType(com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase::class.java).toList() shouldBe listOf("demoMerchantSeedService")
                context.getBeanNamesForType(DemoSeedCommandLine::class.java).toList() shouldBe listOf("demoSeedCommandLine")
            }
        } finally {
            context.close()
        }
    }

    Given("the entry-point arguments in command mode (I-3)") {
        Then("non-web and entitlement-jobs-off flags are prepended; web mode is unchanged") {
            effectiveArguments(arrayOf("--paycore.master.command=demo-seed")).toList() shouldBe listOf(
                "--spring.main.web-application-type=none", "--paycore.master.entitlement.scheduling-enabled=false", "--paycore.master.command=demo-seed",
            )
            effectiveArguments(arrayOf("--server.port=0")).toList() shouldBe listOf("--server.port=0")
            exitCodeOf(IllegalStateException(DemoModeRefusedException())) shouldBe 3
            exitCodeOf(IllegalStateException("x")) shouldNotBe 3
        }
    }
})
