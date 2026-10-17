package com.paycore.master.merchant.domain

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.io.ClassPathResource
import tools.jackson.databind.json.JsonMapper

/**
 * MASTER `SiteChannel` WEB 문법이 harness 공유 벡터(`contracts/host-grammar-vectors.json`, AUTHN-001 검토 n-f)와 같은 판정을 한다 — PAYMENT의 Snapshot v3 수신·
 * 반환 URL host 판정도 같은 파일을 읽는다(세 구현이 갈라지지 않음).
 */
class SiteChannelHostVectorsSpec : FunSpec({

    val vectors = JsonMapper.builder().build().readTree(ClassPathResource("contracts/host-grammar-vectors.json").inputStream)

    test("every accepted vector is a valid WEB channel and every rejected vector is refused") {
        vectors.get("accepted").let { n -> (0 until n.size()).map { n.get(it).stringValue() } }.forEach { host -> withClue(host) { SiteChannel(ChannelType.WEB, host).identifier shouldBe host } }
        vectors.get("rejected").let { n -> (0 until n.size()).map { n.get(it).stringValue() } }.forEach { host -> withClue(host) { shouldThrow<IllegalArgumentException> { SiteChannel(ChannelType.WEB, host) } } }
    }
})
