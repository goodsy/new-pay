package com.paycore.payment.payment.domain

import com.paycore.payment.entitlement.domain.SiteChannelEntry
import com.paycore.payment.entitlement.domain.SnapshotContractViolation
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper

/**
 * PAYMENT의 두 WEB host 판정 — Snapshot v3 수신(`SiteChannelEntry.Web`)과 반환 URL host(`MerchantReturnUrl`) — 이 harness 공유 벡터(AUTHN-001 검토 n-f, MASTER
 * `SiteChannel`과 같은 파일)와 같은 판정을 한다.
 */
class HostGrammarVectorsSpec : FunSpec({

    val vectors = JsonMapper.builder().build().readTree(ClassPathResource("contracts/host-grammar-vectors.json").inputStream)
    val accepted = vectors.get("accepted").let { n -> (0 until n.size()).map { n.get(it).stringValue() } }
    val rejected = vectors.get("rejected").let { n -> (0 until n.size()).map { n.get(it).stringValue() } }

    test("the Snapshot v3 receiver accepts exactly the accepted vectors") {
        accepted.forEach { host -> withClue(host) { SiteChannelEntry.Web(host).host shouldBe host } }
        rejected.forEach { host -> withClue(host) { shouldThrow<SnapshotContractViolation> { SiteChannelEntry.Web(host) } } }
    }

    test("the return URL grammar accepts https://<host>/ exactly for the accepted vectors") {
        accepted.forEach { host -> withClue(host) { MerchantReturnUrl.parse("https://$host/").shouldBeInstanceOf<ReturnUrlParse.Accepted>().url.host shouldBe host } }
        rejected.forEach { host -> withClue(host) { MerchantReturnUrl.parse("https://$host/").shouldBeInstanceOf<ReturnUrlParse.Rejected>() } }
    }
})
