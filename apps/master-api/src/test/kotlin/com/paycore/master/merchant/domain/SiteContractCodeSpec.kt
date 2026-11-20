package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe

/**
 * SITE-CONTRACT-CODE-001 SCODE-1-1(§3.1 형식·C-1·C-8 — 순수 Domain): `site-<가맹점코드>-<NN>`·`ctr-<가맹점코드>-<NN>`.
 * 순번 1..9999, 최소 2자리 0 채움(01..99, 100..9999 — 절단 없음), 9999 초과는 저장된 거절 `*_CODE_EXHAUSTED`(Domain 판정 — 실행기 `beginWrites` 전),
 * 정규식 경계(6·14자 가맹점 코드, 00·1·0001·10000 거절), 정규화 없음(대문자·밑줄·공백 거절), 기존 정규식(내부 키·가맹점 코드·채널 식별값·PolicyCode)과 불충돌.
 * Kotlin 포맷 = SQL 식(1·9·10·99·100·101·999·1000·9999)은 DB CHECK를 쓰는 SiteContractCodeMigrationSpec(SCODE-1-5)이 같은 값으로 확인한다.
 */
class SiteContractCodeSpec : FunSpec({

    val six = MerchantCode("demosh")
    val fourteen = MerchantCode("abcdefghijklmn")

    test("SCODE-1-1 issue: seq 1 → -01, 9 → -09, 10 → -10, 99 → -99, 100 → -100, 101 → -101, 999, 1000, 9999 (padStart 2, never truncated)") {
        val expected = mapOf(1 to "01", 9 to "09", 10 to "10", 99 to "99", 100 to "100", 101 to "101", 999 to "999", 1000 to "1000", 9999 to "9999")
        expected.forEach { (seq, text) ->
            withClue("seq $seq") {
                SiteCode.issue(MerchantCode("demoshop01"), seq).value shouldBe "site-demoshop01-$text"
                ContractCode.issue(MerchantCode("demoshop01"), seq).value shouldBe "ctr-demoshop01-$text"
                SiteCode.issue(MerchantCode("demoshop01"), seq).seq shouldBe seq
                ContractCode.issue(MerchantCode("demoshop01"), seq).issuedMerchantCode shouldBe MerchantCode("demoshop01")
            }
        }
    }

    test("SCODE-1-1 exhaustion: the 10000th code is a stored business rejection (SITE_CODE_EXHAUSTED / CONTRACT_CODE_EXHAUSTED), not an input error") {
        shouldThrow<MasterRuleViolation> { SiteCode.issue(six, 10_000) }.rejection shouldBe MasterRejection.SITE_CODE_EXHAUSTED
        shouldThrow<MasterRuleViolation> { ContractCode.issue(six, 10_000) }.rejection shouldBe MasterRejection.CONTRACT_CODE_EXHAUSTED
        // 0 이하 순번은 호출 결함(MAX+1은 항상 1 이상) — 업무 거절이 아니다.
        shouldThrow<IllegalArgumentException> { SiteCode.issue(six, 0) }
        shouldThrow<IllegalArgumentException> { ContractCode.issue(six, -1) }
    }

    test("SCODE-1-1 format boundaries: 6/14-char merchant codes, 01·99·100·9999 pass; 00·1·0001·10000, upper case, underscore, space are rejected (no normalization)") {
        listOf(
            "site-demosh-01", "site-abcdefghijklmn-99", "site-demoshop01-100", "site-demoshop01-9999",
            SiteCode.issue(six, 1).value, SiteCode.issue(fourteen, 9999).value,
        ).forEach { withClue(it) { SiteCode.FORMAT.matches(it) shouldBe true; SiteCode.parse(it).value shouldBe it } }
        listOf(
            "ctr-demosh-01", "ctr-abcdefghijklmn-99", "ctr-demoshop01-100", "ctr-demoshop01-9999",
        ).forEach { withClue(it) { ContractCode.FORMAT.matches(it) shouldBe true; ContractCode.parse(it).value shouldBe it } }
        listOf(
            "site-demoshop01-00", "site-demoshop01-1", "site-demoshop01-0001", "site-demoshop01-10000", "site-demoshop01-010",
            "Site-demoshop01-01", "site-Demoshop01-01", "site_demoshop01_01", "site-demoshop01_01", "site-demo shop-01", " site-demoshop01-01",
            "site-demos-01", "site-abcdefghijklmno-01", "site-1demoshop-01", "ctr-demoshop01-01", "site-demoshop01-01 ",
        ).forEach { withClue(it) { SiteCode.FORMAT.matches(it) shouldBe false; shouldThrow<IllegalArgumentException> { SiteCode.parse(it) } } }
        listOf("ctr-demoshop01-00", "ctr-demoshop01-1", "CTR-demoshop01-01", "ctr_demoshop01_01", "site-demoshop01-01").forEach {
            withClue(it) { ContractCode.FORMAT.matches(it) shouldBe false; shouldThrow<IllegalArgumentException> { ContractCode.parse(it) } }
        }
        // 마지막 '-' 기준 분리는 모호하지 않다(가맹점 코드에 '-' 없음).
        SiteCode.parse("site-demoshop01-101").let { listOf(it.issuedMerchantCode.value, it.seq) } shouldBe listOf("demoshop01", 101)
        ContractCode.parse("ctr-abcdefghijklmn-9999").let { listOf(it.issuedMerchantCode.value, it.seq) } shouldBe listOf("abcdefghijklmn", 9999)
    }

    test("SCODE-1-1 property: generated codes never match the internal-key / merchant-code / channel / PolicyCode grammars (no misreading as another identifier)") {
        val other = mapOf(
            "internal key" to Regex("^[a-z]{2,4}_[0-9A-HJKMNP-TV-Z]{26}$"),
            "merchant code" to MerchantCode.FORMAT,
            "policy code" to Regex("^[A-Z][A-Z0-9_]*$"),
        )
        val merchantCodes = listOf("demosh", "demoshop01", "abcdefghijklmn", "m0123456789abc", "site01", "ctrxyz")
        val generated = merchantCodes.flatMap { mc -> listOf(1, 9, 10, 99, 100, 101, 999, 1000, 9999).flatMap { n -> listOf(SiteCode.issue(MerchantCode(mc), n).value, ContractCode.issue(MerchantCode(mc), n).value) } }
        generated.flatMap { code -> other.filter { (_, r) -> r.matches(code) }.map { (name, _) -> "$code ~ $name" } }.shouldBeEmpty()
        // 채널 식별값(WEB host·APP 식별자는 '.' 필수 — Domain SiteChannel 생성자가 판정)으로도 받아들여지지 않는다.
        generated.filter { code -> ChannelType.entries.any { t -> runCatching { SiteChannel(t, code) }.isSuccess } }.shouldBeEmpty()
        generated.filter { runCatching { MasterIdFormat.requireValid(it, "site", "x") }.isSuccess || runCatching { MasterIdFormat.requireValid(it, "ctr", "x") }.isSuccess }.shouldBeEmpty()
        // 두 종류는 접두어가 달라 교차 충돌하지 않는다(Q-SC-3).
        generated.filter { SiteCode.FORMAT.matches(it) && ContractCode.FORMAT.matches(it) }.shouldBeEmpty()
        generated.toSet().size shouldBe generated.size
    }

    test("SCODE-1-1 equality is by value (rows read back compare equal to the issued code)") {
        SiteCode.parse("site-demoshop01-02") shouldBe SiteCode.issue(MerchantCode("demoshop01"), 2)
        ContractCode.parse("ctr-demoshop01-02") shouldBe ContractCode.issue(MerchantCode("demoshop01"), 2)
        SiteCode.issue(MerchantCode("demoshop01"), 2).toString() shouldBe "site-demoshop01-02"
    }
})
