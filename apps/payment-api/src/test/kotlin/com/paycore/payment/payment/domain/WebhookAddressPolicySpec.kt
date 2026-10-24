package com.paycore.payment.payment.domain

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

/**
 * 목적지 주소 판정(WEBHOOK-001 §7.2·§21.0b, D-WH-007, 검토 m-6 — 기본 거부). 기대값은 명세 대역 표에서 직접 적는다. 입력은 주소 바이트(4 = IPv4, 16 = IPv6 — domain은 java.net을 쓰지 않는다).
 * IPv6는 2000::/3 전역 unicast 안에서 금지 대역이 아닐 때만, IPv4는 금지 대역이 아닐 때만 공인이다. IPv4-mapped·호환·6to4·NAT64·Teredo는 감싼 v4와 무관하게 차단한다.
 */
class WebhookAddressPolicySpec : BehaviorSpec({

    fun v4(s: String): ByteArray = s.split('.').map { it.toInt().toByte() }.toByteArray()

    fun v6(s: String): ByteArray {
        val (head, tail) = if ("::" in s) s.split("::", limit = 2).let { it[0] to it[1] } else s to null
        val h = head.split(':').filter { it.isNotEmpty() }.map { it.toInt(16) }
        val t = tail?.split(':')?.filter { it.isNotEmpty() }?.map { it.toInt(16) } ?: emptyList()
        val groups = if (tail == null) h else h + List(8 - h.size - t.size) { 0 } + t
        require(groups.size == 8)
        return groups.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray()
    }

    Given("IPv4 special-purpose ranges (blocked)") {
        mapOf(
            "0.0.0.0" to "THIS_NETWORK", "0.255.255.255" to "THIS_NETWORK",
            "10.0.0.1" to "PRIVATE", "10.255.255.255" to "PRIVATE",
            "100.64.0.1" to "SHARED_CGNAT", "100.127.255.255" to "SHARED_CGNAT",
            "127.0.0.1" to "LOOPBACK", "127.255.255.254" to "LOOPBACK",
            "169.254.169.254" to "LINK_LOCAL",
            "172.16.0.1" to "PRIVATE", "172.31.255.255" to "PRIVATE",
            "192.0.0.1" to "IETF_PROTOCOL", "192.0.2.10" to "DOCUMENTATION", "192.88.99.1" to "RELAY_6TO4",
            "192.168.0.1" to "PRIVATE", "198.18.0.1" to "BENCHMARK", "198.19.255.255" to "BENCHMARK",
            "198.51.100.7" to "DOCUMENTATION", "203.0.113.9" to "DOCUMENTATION",
            "224.0.0.1" to "MULTICAST", "239.255.255.255" to "MULTICAST",
            "240.0.0.1" to "RESERVED", "255.255.255.255" to "RESERVED",
        ).forEach { (ip, reason) ->
            Then("$ip is blocked as $reason") { WebhookAddressPolicy.classify(v4(ip)) shouldBe WebhookAddressClass.Blocked(reason) }
        }
    }

    Given("IPv4 public neighbours of the blocked ranges") {
        listOf("1.1.1.1", "9.255.255.255", "11.0.0.0", "100.63.255.255", "100.128.0.0", "126.255.255.255", "128.0.0.0", "169.253.255.255", "169.255.0.0",
            "172.15.255.255", "172.32.0.0", "192.0.1.0", "192.0.3.0", "192.88.98.255", "192.88.100.0", "192.167.255.255", "192.169.0.0", "198.17.255.255",
            "198.20.0.0", "198.51.99.255", "198.51.101.0", "203.0.112.255", "203.0.114.0", "223.255.255.255").forEach { ip ->
            Then("$ip is public") { WebhookAddressPolicy.classify(v4(ip)) shouldBe WebhookAddressClass.Public }
        }
    }

    Given("IPv6 (default deny outside 2000::/3)") {
        mapOf(
            "::" to "UNSPECIFIED", "::1" to "LOOPBACK", "::a00:1" to "IPV4_COMPATIBLE", "::ffff:93b8:d822" to "IPV4_MAPPED", "::ffff:7f00:1" to "IPV4_MAPPED",
            "64:ff9b::808:808" to "NAT64", "100::1" to "DISCARD", "2001::1" to "TEREDO", "2001:db8::1" to "DOCUMENTATION", "2002:808:808::1" to "RELAY_6TO4",
            "fc00::1" to "UNIQUE_LOCAL", "fdff::1" to "UNIQUE_LOCAL", "fe80::1" to "LINK_LOCAL", "febf::1" to "LINK_LOCAL", "fec0::1" to "SITE_LOCAL",
            "ff02::1" to "MULTICAST", "4000::1" to "NOT_GLOBAL_UNICAST", "1fff::1" to "NOT_GLOBAL_UNICAST",
            // 검토 #2 n-2(IANA IPv6 special-purpose registry 재확인): 2001:2::/48 benchmarking(전역 도달 False), 2001:10::/28 ORCHID(폐기), 2001:20::/28 ORCHIDv2
            // (레지스트리상 전역 도달 True지만 overlay 식별자라 HTTP 목적지가 아님 — 기본 거부), 3fff::/20 문서용(2024, 전역 도달 False).
            "2001:2::1" to "BENCHMARK", "2001:2:0:ffff::1" to "BENCHMARK", "2001:10::1" to "ORCHID", "2001:1f::1" to "ORCHID", "2001:20::1" to "ORCHID_V2",
            "2001:2f::1" to "ORCHID_V2", "3fff::1" to "DOCUMENTATION", "3fff:fff::1" to "DOCUMENTATION",
        ).forEach { (ip, reason) ->
            Then("$ip is blocked as $reason") { WebhookAddressPolicy.classify(v6(ip)) shouldBe WebhookAddressClass.Blocked(reason) }
        }
        listOf("2606:4700:4700::1111", "2001:4860:4860::8888", "2a00:1450::1", "3fff:ffff::1", "2001:1::1").forEach { ip ->
            Then("$ip is public") { WebhookAddressPolicy.classify(v6(ip)) shouldBe WebhookAddressClass.Public }
        }
    }

    Given("a resolution of several addresses") {
        Then("all must be public — one blocked address blocks the destination") {
            WebhookAddressPolicy.classifyAll(listOf(v4("1.1.1.1"), v4("10.0.0.1"))) shouldBe WebhookAddressClass.Blocked("PRIVATE")
            WebhookAddressPolicy.classifyAll(listOf(v4("1.1.1.1"), v6("2606:4700:4700::1111"))) shouldBe WebhookAddressClass.Public
            WebhookAddressPolicy.classifyAll(emptyList()) shouldBe WebhookAddressClass.Blocked("NO_ADDRESS")
        }
        Then("a byte length other than 4 or 16 is blocked") {
            WebhookAddressPolicy.classify(ByteArray(5)) shouldBe WebhookAddressClass.Blocked("MALFORMED")
        }
    }
})
