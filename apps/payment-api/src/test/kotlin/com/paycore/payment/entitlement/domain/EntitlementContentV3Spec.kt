package com.paycore.payment.entitlement.domain

import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithChannels
import com.paycore.payment.entitlement.domain.SnapshotFixture.entitledWithTerms
import com.paycore.payment.entitlement.domain.SnapshotFixture.ids
import com.paycore.payment.entitlement.domain.SnapshotFixture.mapSite
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * Snapshot schemaVersion 3 수신(AUTHN-001 §9.1·§9.3, D-AUTHN-002): v2 + `sites[].channel`. PAYMENT 해석기는 MASTER를 믿지 않고 다시 확인한다 —
 * WEB host는 MASTER `SiteChannel` WEB 문법(소문자, label 둘 이상, ≤253, IP 리터럴 아님), APP은 type만. 위반·버전과 필드 불일치는 계약 위반(저장 없음).
 */
class EntitlementContentV3Spec : FunSpec({

    test("a v3 content round-trips through its canonical JSON with the same hash; v1/v2 canonical forms carry no channel") {
        val ids = ids()
        val v3 = entitledWithChannels(ids)
        val parsed = EntitlementContent.fromJson(MiniJson.parse(v3.canonical), 3, allowLeaseFields = false)
        // 정규 형식은 Site를 siteId로 정렬한다 — 목록 순서가 아니라 정규 내용·hash로 비교한다.
        parsed.canonical shouldBe v3.canonical
        parsed.sites.sortedBy { it.siteId } shouldBe v3.sites.sortedBy { it.siteId }
        parsed.contentHash shouldBe v3.contentHash
        parsed.sites.first { it.siteId == ids.siteId }.channel shouldBe SiteChannelEntry.Web(SnapshotFixture.WEB_HOST)
        parsed.sites.first { it.siteId == ids.otherSiteId }.channel shouldBe SiteChannelEntry.App
        v3.canonical.contains("\"channel\":{\"host\":\"${SnapshotFixture.WEB_HOST}\",\"type\":\"WEB\"}") shouldBe true
        v3.canonical.contains("\"channel\":{\"type\":\"APP\"}") shouldBe true
        entitledWithTerms(ids).canonical.contains("channel") shouldBe false
        v3.contentHash shouldNotBe entitledWithTerms(ids).copy(schemaVersion = 2).contentHash
    }

    test("channels are carried exactly from schema version 3 (v2 + channel, v3 without channel, constructor mismatch)") {
        val ids = ids()
        val v3 = entitledWithChannels(ids).canonical
        val v2 = entitledWithTerms(ids).canonical
        listOf<Pair<String, () -> Any>>(
            "v3 json read as v2" to { EntitlementContent.fromJson(MiniJson.parse(v3), 2, allowLeaseFields = false) },
            "v2 json read as v3" to { EntitlementContent.fromJson(MiniJson.parse(v2), 3, allowLeaseFields = false) },
            "v3 content without channels" to { entitledWithChannels(ids).copy(sites = entitledWithTerms(ids).sites) },
            "v2 content with channels" to { entitledWithChannels(ids).copy(schemaVersion = 2) },
        ).forEach { (name, run) -> withClue(name) { shouldThrow<SnapshotContractViolation> { run() } } }
    }

    test("the receiver re-checks the WEB host grammar (same judgement as MASTER SiteChannel) and the channel shape") {
        val ids = ids()
        val badHosts = listOf(
            "Shop.example.test", "shop.example.test.", "localhost", "127.0.0.1", "a.0x7f", "-a.example.test", "a-.example.test", "a..example.test",
            "shop.example.test:443", "user@shop.example.test", "샵.example.test", "${"a".repeat(64)}.example.test",
            (List(3) { "a".repeat(63) } + "b".repeat(62)).joinToString("."),
        )
        badHosts.forEach { host ->
            withClue(host) { shouldThrow<SnapshotContractViolation> { entitledWithChannels(ids, webHost = host) } }
        }
        entitledWithChannels(ids, webHost = (List(3) { "a".repeat(63) } + "b".repeat(61)).joinToString(".")).sites.size shouldBe 2
        val v3 = entitledWithChannels(ids).canonical
        listOf(
            "APP with a host" to v3.replace("{\"type\":\"APP\"}", "{\"host\":\"app.example.test\",\"type\":\"APP\"}"),
            "WEB without a host" to v3.replace("{\"host\":\"${SnapshotFixture.WEB_HOST}\",\"type\":\"WEB\"}", "{\"type\":\"WEB\"}"),
            "unknown channel type" to v3.replace("{\"type\":\"APP\"}", "{\"type\":\"IOS\"}"),
            "extra channel field" to v3.replace("{\"type\":\"APP\"}", "{\"appId\":\"com.example.shop\",\"type\":\"APP\"}"),
            "channel not an object" to v3.replace("{\"type\":\"APP\"}", "\"APP\""),
        ).forEach { (name, json) ->
            withClue(name) {
                json shouldNotBe v3
                shouldThrow<SnapshotContractViolation> { EntitlementContent.fromJson(MiniJson.parse(json), 3, allowLeaseFields = false) }
            }
        }
    }

    test("a terminated site keeps its channel in the content (the host query, not the receiver, refuses it)") {
        val ids = ids()
        val content = entitledWithChannels(ids).mapSite(ids.siteId) { it.copy(lifecycle = Lifecycle.TERMINATED) }
        val parsed = EntitlementContent.fromJson(MiniJson.parse(content.canonical), 3, allowLeaseFields = false)
        parsed.canonical shouldBe content.canonical
        parsed.sites.first { it.siteId == ids.siteId }.let { it.lifecycle to it.channel } shouldBe (Lifecycle.TERMINATED to SiteChannelEntry.Web(SnapshotFixture.WEB_HOST))
    }
})
