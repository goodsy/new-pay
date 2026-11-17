package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithChannels
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithCode
import com.paycore.payment.entitlement.domain.SnapshotFixture.headOf
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.incoming
import com.paycore.payment.entitlement.domain.SnapshotFixture.leaseAt
import com.paycore.payment.entitlement.domain.SnapshotFixture.termsIds
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Snapshot schemaVersion 4 수신(MERCHANT-CODE-001 §6·§8 MC-4-1·4·5, D-MCODE-001 Q-MC-1·D-MCODE-002 Q-MC-8): v3 + 최상위 `merchantCode`(가맹점 코드 — 출력 전용 표시값).
 * PAYMENT는 MASTER를 믿지 않고 코드 형식(`^[a-z][a-z0-9]{5,13}$`)과 "v4에만 정확히 있음"을 다시 확인한다. 코드는 권리 판정 입력이 아니다(금액·권리 의미 불변 — 같은 권리 판정).
 * 같은 revision·다른 코드는 기존 무결성 규칙대로 INTEGRITY_CONFLICT, revision이 오르며 코드가 바뀌면 정상 적용 + "MID 있는 가맹점 코드 변경" 신호(경보 `merchant_code_changed_with_mid`).
 */
class EntitlementContentV4Spec : FunSpec({

    test("MC-4-1: a v4 content round-trips through its canonical JSON with the same hash; v1~v3 canonical forms carry no merchantCode") {
        val ids = ids()
        val terms = termsIds()
        val v4 = entitledWithCode(ids, terms)
        val parsed = EntitlementContent.fromJson(MiniJson.parse(v4.canonical), 4, allowLeaseFields = false)
        parsed.canonical shouldBe v4.canonical
        parsed.contentHash shouldBe v4.contentHash
        parsed.merchantCode shouldBe SnapshotFixture.MERCHANT_CODE
        v4.canonical.contains("\"merchantCode\":\"${SnapshotFixture.MERCHANT_CODE}\"") shouldBe true
        val v3 = entitledWithChannels(ids, terms)
        listOf(1, 2, 3).forEach { version -> withClue(version) { SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS.contains(version) shouldBe true } }
        v3.canonical.contains("merchantCode") shouldBe false
        v3.merchantCode shouldBe null
        v4.contentHash shouldNotBe v3.contentHash
        // 권리 판정 입력은 v3와 같다(코드는 금액·권리 의미 없음 — Financial Consistency).
        v4.copy(schemaVersion = CHANNEL_SNAPSHOT_SCHEMA_VERSION, merchantCode = null) shouldBe v3
        SUPPORTED_SNAPSHOT_SCHEMA_VERSIONS shouldBe setOf(1, 2, 3, 4)
        isSupportedSnapshotSchema(SnapshotFixture.UNSUPPORTED_SCHEMA_VERSION) shouldBe false
    }

    test("merchantCode is carried exactly from schema version 4 (v3 + code, v4 without code, constructor mismatch)") {
        val ids = ids()
        val v4 = entitledWithCode(ids).canonical
        val v3 = entitledWithChannels(ids).canonical
        listOf<Pair<String, () -> Any>>(
            "v4 json read as v3" to { EntitlementContent.fromJson(MiniJson.parse(v4), 3, allowLeaseFields = false) },
            "v3 json read as v4" to { EntitlementContent.fromJson(MiniJson.parse(v3), 4, allowLeaseFields = false) },
            "v4 content without code" to { entitledWithCode(ids).copy(merchantCode = null) },
            "v3 content with code" to { entitledWithChannels(ids).copy(merchantCode = SnapshotFixture.MERCHANT_CODE) },
        ).forEach { (name, run) -> withClue(name) { shouldThrow<SnapshotContractViolation> { run() } } }
    }

    test("the receiver re-checks the merchant code format (no normalisation; internal keys are never a code)") {
        val ids = ids()
        listOf("Shopabc1", "shop", "1shopabc", "shop_abc1", "shop-abc1", "shop abc1", "a".repeat(15), "mer_01J9ZQG1DENM1", "biz_01J9ZQG1DENB1", "").forEach { bad ->
            withClue(bad) { shouldThrow<SnapshotContractViolation> { entitledWithCode(ids, code = bad) } }
        }
        listOf("abcdef", "a".repeat(14), "goldshop01").forEach { ok -> withClue(ok) { entitledWithCode(ids, code = ok).merchantCode shouldBe ok } }
        val v4 = entitledWithCode(ids).canonical
        listOf(
            "number" to v4.replace("\"merchantCode\":\"${SnapshotFixture.MERCHANT_CODE}\"", "\"merchantCode\":7"),
            "null" to v4.replace("\"merchantCode\":\"${SnapshotFixture.MERCHANT_CODE}\"", "\"merchantCode\":null"),
            "upper case" to v4.replace("\"merchantCode\":\"${SnapshotFixture.MERCHANT_CODE}\"", "\"merchantCode\":\"FIXSHOP01\""),
        ).forEach { (name, json) -> withClue(name) { shouldThrow<SnapshotContractViolation> { EntitlementContent.fromJson(MiniJson.parse(json), 4, allowLeaseFields = false) } } }
    }

    test("MC-4-1/5: same revision with a different code → INTEGRITY_CONFLICT (head blocked), never silently applied") {
        val ids = ids()
        val terms = termsIds()
        val head = headOf(entitledWithCode(ids, terms, revision = 3))
        head.merchantCode shouldBe SnapshotFixture.MERCHANT_CODE
        val other = SnapshotApplicationRule.decide(head, incoming(entitledWithCode(ids, terms, revision = 3, code = "othershop01")))
        other.outcome shouldBe InboxOutcome.INTEGRITY_CONFLICT
        other.newHead!!.blocked shouldBe HeadBlock.INTEGRITY_CONFLICT
        other.newHead.merchantCode shouldBe SnapshotFixture.MERCHANT_CODE
        other.merchantCodeChanged shouldBe false
        // 대조(MC-4-5): v3 head와 같은 revision에 v4 내용을 직접 넣으면(= 같은 revision 재발행) 다른 hash → INTEGRITY_CONFLICT. 승격이 revision +1이어야 하는 근거.
        val v3Head = headOf(entitledWithChannels(ids, terms, revision = 3))
        SnapshotApplicationRule.decide(v3Head, incoming(entitledWithCode(ids, terms, revision = 3))).outcome shouldBe InboxOutcome.INTEGRITY_CONFLICT
        // 정순(MC-4-5): revision +1의 v4 승격은 APPLIED이고 head가 코드를 갖는다(코드가 처음 생긴 것은 "변경"이 아니다).
        val promoted = SnapshotApplicationRule.decide(v3Head, incoming(entitledWithCode(ids, terms, revision = 4)))
        promoted.outcome shouldBe InboxOutcome.APPLIED
        promoted.newHead!!.merchantCode shouldBe SnapshotFixture.MERCHANT_CODE
        promoted.newHead.schemaVersion shouldBe 4
        promoted.merchantCodeChanged shouldBe false
    }

    test("MC-4-4: a code change at a higher revision applies normally but is flagged (a merchant with a MID never changes its code — alert)") {
        val ids = ids()
        val terms = termsIds()
        val head = headOf(entitledWithCode(ids, terms, revision = 3))
        val changed = SnapshotApplicationRule.decide(head, incoming(entitledWithCode(ids, terms, revision = 4, code = "othershop01")))
        changed.outcome shouldBe InboxOutcome.APPLIED
        changed.newHead!!.merchantCode shouldBe "othershop01"
        changed.merchantCodeChanged shouldBe true
        val same = SnapshotApplicationRule.decide(head, incoming(entitledWithCode(ids, terms, revision = 4)))
        same.merchantCodeChanged shouldBe false
        // 같은 revision·같은 hash의 lease 갱신은 코드를 그대로 둔다.
        val renewed = SnapshotApplicationRule.decide(head, incoming(entitledWithCode(ids, terms, revision = 3), leaseAt(SnapshotFixture.t0.plusSeconds(30), seq = 1)))
        renewed.outcome shouldBe InboxOutcome.LEASE_EXTENDED
        renewed.newHead!!.merchantCode shouldBe SnapshotFixture.MERCHANT_CODE
    }

    test("MC-4-4 (review MINOR-1): a code that disappears at a higher revision (v4 head → v3 snapshot) applies but is flagged like a change (same alert counter)") {
        val ids = ids()
        val terms = termsIds()
        val head = headOf(entitledWithCode(ids, terms, revision = 3))
        // MASTER가 v4 이전으로 되돌아가 더 높은 revision을 v3로 내면 외부에 이미 나간 코드가 응답·웹훅에서 사라진다 — 나간 코드의 변경과 같은 불변식 위반.
        val removed = SnapshotApplicationRule.decide(head, incoming(entitledWithChannels(ids, terms, revision = 4)))
        removed.outcome shouldBe InboxOutcome.APPLIED
        removed.newHead!!.merchantCode shouldBe null
        removed.merchantCodeChanged shouldBe true
        // 대조: 코드가 없던 head(v3)가 계속 코드 없이 갱신되는 것은 경보가 아니다.
        val v3Head = headOf(entitledWithChannels(ids, terms, revision = 3))
        SnapshotApplicationRule.decide(v3Head, incoming(entitledWithChannels(ids, terms, revision = 4))).merchantCodeChanged shouldBe false
    }

    test("an empty head carries no code; a filled head without a v4 snapshot carries no code") {
        EntitlementHead.empty("mer_x").merchantCode shouldBe null
        shouldThrow<IllegalArgumentException> { EntitlementHead.empty("mer_x").copy(merchantCode = SnapshotFixture.MERCHANT_CODE) }
        headOf(entitledWithChannels(ids())).merchantCode shouldBe null
    }
})
