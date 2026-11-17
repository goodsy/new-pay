package com.paycore.admin.approval

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * MERCHANT-CODE-001 MC-3-24(§5.6.4 — rev 0.4.7 MAJOR-1, C-36) 소스 검사: approval 감사 행의 대상 열은 `ApprovalAuditTarget` 한 곳에서만 만든다.
 * `approval/application` 아래·`merchantops/application` 아래에서 `AuditRecord(`를 만드는 파일이 `targetRef =`·`targetMerchantId =` 인자를 직접 넘기는 곳은
 * `ApprovalAuditTarget.kt`(helper)와 C-32 표의 두 merchantops 지점(`MerchantLookupService`·`MerchantRegistrationService`)뿐이어야 하고, `targetRef = ….target.ref` 패턴은 0건이다
 * (새 작성 지점이 helper를 우회하면 코드 표기 승인의 감사가 V15 CHECK ③에 막혀 TX2·회복·만료가 영구 실패한다).
 * 시험 작업 디렉터리 = 모듈 디렉터리(Gradle 기본). 스캔 대상 파일 수 > 0을 함께 단언해 경로 오류가 조용히 통과하지 않게 한다.
 */
class ApprovalAuditTargetSourceSpec : FunSpec({

    val roots = listOf("src/main/kotlin/com/paycore/admin/approval/application", "src/main/kotlin/com/paycore/admin/merchantops/application").map(::File)
    // 기대값 변경(BUSINESS-PROFILE-001 BIZ-3 — C-14·D-BIZ-003): 사업자 관리 감사 `BusinessAudit.kt`를 작성 지점에 추가. 조건 = 대상 열은 `biz_` 사업자 ID나 null만
    // (가맹점·승인 대상 표기 불가 — 아래 마지막 시험과 BusinessAuditTargetSpec), `targetMerchantId` 없음.
    val allowed = setOf("ApprovalAuditTarget.kt", "MerchantLookupService.kt", "MerchantRegistrationService.kt", "BusinessAudit.kt")

    /** 주석(한 줄 `//`, KDoc·블록 `*`)을 뺀 코드 줄. */
    fun code(file: File): String = file.readLines().filterNot { val t = it.trimStart(); t.startsWith("//") || t.startsWith("*") || t.startsWith("/*") }.joinToString("\n")

    val files = roots.flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
    val namedTarget = Regex("""(?<![\w.])(targetRef|targetMerchantId)\s*=(?!=)""")
    val declared = Regex("""\b(val|var)\s+(targetRef|targetMerchantId)\s*=""")
    val parameterDefault = Regex("""(targetRef|targetMerchantId)\s*:\s*String\?\s*=""")

    test("the scan sees the sources (approval + merchantops application files)") {
        files.size shouldBeGreaterThan 20
        files.map { it.name }.containsAll(allowed) shouldBe true
    }

    test("AuditRecord target columns are passed only by ApprovalAuditTarget.kt and the two C-32 merchantops writers") {
        val offenders = files.filter { it.name !in allowed }.mapNotNull { f ->
            val text = code(f)
            if (!text.contains("AuditRecord(")) return@mapNotNull null
            val hits = namedTarget.findAll(text).filterNot { m ->
                val lineStart = text.lastIndexOf('\n', m.range.first) + 1
                val line = text.substring(lineStart, text.indexOf('\n', m.range.first).let { if (it < 0) text.length else it })
                declared.containsMatchIn(line) || parameterDefault.containsMatchIn(line)
            }.toList()
            if (hits.isEmpty()) null else f.name
        }
        withClue("files writing audit target columns outside ApprovalAuditTarget") { offenders.shouldBeEmpty() }
    }

    test("no audit target written from a raw approval target reference (targetRef = ….target.ref)") {
        val pattern = Regex("""targetRef\s*=\s*[^,\n)]*\.target\.ref\b""")
        files.filter { pattern.containsMatchIn(code(it)) }.map { it.name }.shouldBeEmpty()
    }

    test("BusinessAudit.kt (BUSINESS-PROFILE-001) writes no targetMerchantId and guards targetRef to a business id before writing") {
        val text = code(files.single { it.name == "BusinessAudit.kt" })
        Regex("""\btargetMerchantId\s*=""").containsMatchIn(text) shouldBe false
        text.contains("require(targetRef == null || BUSINESS_ID.matches(targetRef))") shouldBe true
        text.indexOf("BUSINESS_ID.matches(targetRef)") shouldBeGreaterThan -1
        (text.indexOf("BUSINESS_ID.matches(targetRef)") < text.indexOf("AuditRecord(")) shouldBe true
    }

    test("every approval-application audit record goes through the helper: no AuditRecord( constructor call outside ApprovalAuditTarget.kt in approval/application") {
        files.filter { it.path.replace('\\', '/').contains("/approval/application/") && it.name != "ApprovalAuditTarget.kt" }
            .filter { Regex("""\bAuditRecord\(""").containsMatchIn(code(it)) }.map { it.name }.shouldBeEmpty()
    }
})
