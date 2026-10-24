package com.paycore.payment.architecture

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * WEBHOOK-001 검토 #1 n-5·D-WH-009: runtime 계정은 outbox_event 알림 열 UPDATE 권한 때문에 Outbox 잠금 읽기도 할 수 있다(수용한 부수 효과). 그래서 main 코드·SQL에
 * `outbox_event` 잠금 읽기(`FOR UPDATE`·`FOR SHARE`)가 없음을 소스 검사로 고정한다 — 업무 TX의 Outbox INSERT가 fan-out을 기다리지 않는다(H-3).
 * 주석은 제거한 뒤 검사한다(설명 문장은 대상 아님). 한 SQL 문장 안(`;`·문자열 끝 전, 400자 이내)에 `outbox_event`와 잠금 절이 함께 있으면 위반이다.
 */
class OutboxLockReadScanSpec : FunSpec({

    val violation = Regex("(?is)\\boutbox_event\\b[^;\"`]{0,400}?\\bFOR\\s+(UPDATE|SHARE)\\b")

    fun stripComments(text: String, sql: Boolean): String {
        val noBlock = text.replace(Regex("(?s)/\\*.*?\\*/"), " ")
        return noBlock.lines().joinToString("\n") { line ->
            val cut = if (sql) line.indexOf("--") else line.indexOf("//").takeUnless { i -> i > 0 && line[i - 1] == ':' } ?: -1
            if (cut >= 0) line.substring(0, cut) else line
        }
    }

    fun violations(root: File): List<String> = root.walkTopDown()
        .filter { it.isFile && (it.extension == "kt" || it.extension == "sql") }
        .filter { f -> violation.containsMatchIn(stripComments(f.readText(), f.extension == "sql")) }
        .map { it.path }
        .toList()

    test("main Kotlin and SQL never lock-read outbox_event") {
        val main = File("src/main")
        main.isDirectory shouldBe true
        violations(main).shouldBeEmpty()
    }

    test("the scan detects a violation and ignores comments (self-test)") {
        val dir = kotlin.io.path.createTempDirectory("outbox-scan").toFile()
        try {
            File(dir, "Bad.kt").writeText("val q = \"SELECT id FROM outbox_event WHERE id = :id FOR UPDATE\"\n")
            File(dir, "BadShare.sql").writeText("SELECT id\nFROM outbox_event\nWHERE id = 1\nFOR SHARE;\n")
            File(dir, "Commented.kt").writeText("// outbox_event FOR UPDATE is forbidden\n/* outbox_event … FOR SHARE */\nval x = 1\n")
            File(dir, "CommentedSql.sql").writeText("-- outbox_event 잠금 읽기(SELECT … FOR UPDATE)\nSELECT 1;\n")
            File(dir, "OtherTable.kt").writeText("val q = \"SELECT guard_name FROM merchant_webhook_fanout_guard WHERE guard_name = 'FANOUT' FOR UPDATE\"\n")
            violations(dir).map { File(it).name }.sorted() shouldBe listOf("Bad.kt", "BadShare.sql")
        } finally {
            dir.deleteRecursively()
        }
    }
})
