package com.paycore.payment.payment.domain

/** 목적지 주소 판정 결과. [Blocked.reason]은 고정 분류 이름(로그·시도 기록용 — 주소 원문은 남기지 않는다). */
sealed interface WebhookAddressClass {
    data object Public : WebhookAddressClass

    data class Blocked(val reason: String) : WebhookAddressClass
}

/**
 * 가맹점 Webhook 목적지 주소 정책(WEBHOOK-001 §7.2·§21.0b, D-WH-007, 검토 m-6 — **기본 거부**). 입력은 해석된 주소 바이트(IPv4 4바이트, IPv6 16바이트). IPv6는 2000::/3 전역 unicast 안에서
 * 금지 대역이 아닐 때만, IPv4는 IANA 특수 용도 대역이 아닐 때만 공인이다. IPv4-mapped·호환·6to4·NAT64·Teredo는 감싼 IPv4와 무관하게 차단한다(우회 경로를 판정하지 않고 닫는다).
 * TEST 시험 수신기 예외(정확한 host:port allowlist)는 이 정책 밖 — 전송 adapter가 전달 행 environment가 TEST일 때만 적용한다.
 */
object WebhookAddressPolicy {

    private class Range(val bytes: ByteArray, val prefix: Int, val reason: String) {
        fun contains(address: ByteArray): Boolean {
            if (address.size != bytes.size) return false
            var bits = prefix
            var i = 0
            while (bits > 0) {
                val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
                if ((address[i].toInt() and mask) != (bytes[i].toInt() and mask)) return false
                bits -= 8
                i++
            }
            return true
        }
    }

    private fun v4(a: Int, b: Int, c: Int, d: Int, prefix: Int, reason: String) = Range(byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte()), prefix, reason)

    private fun v6(groups: List<Int>, prefix: Int, reason: String): Range {
        val full = groups + List(8 - groups.size) { 0 }
        return Range(full.flatMap { listOf((it shr 8).toByte(), it.toByte()) }.toByteArray(), prefix, reason)
    }

    private val V4_BLOCKED = listOf(
        v4(0, 0, 0, 0, 8, "THIS_NETWORK"),
        v4(10, 0, 0, 0, 8, "PRIVATE"),
        v4(100, 64, 0, 0, 10, "SHARED_CGNAT"),
        v4(127, 0, 0, 0, 8, "LOOPBACK"),
        v4(169, 254, 0, 0, 16, "LINK_LOCAL"),
        v4(172, 16, 0, 0, 12, "PRIVATE"),
        v4(192, 0, 0, 0, 24, "IETF_PROTOCOL"),
        v4(192, 0, 2, 0, 24, "DOCUMENTATION"),
        v4(192, 88, 99, 0, 24, "RELAY_6TO4"),
        v4(192, 168, 0, 0, 16, "PRIVATE"),
        v4(198, 18, 0, 0, 15, "BENCHMARK"),
        v4(198, 51, 100, 0, 24, "DOCUMENTATION"),
        v4(203, 0, 113, 0, 24, "DOCUMENTATION"),
        v4(224, 0, 0, 0, 4, "MULTICAST"),
        v4(240, 0, 0, 0, 4, "RESERVED"),
    )

    private val V6_BLOCKED = listOf(
        v6(emptyList(), 128, "UNSPECIFIED"),
        v6(listOf(0, 0, 0, 0, 0, 0, 0, 1), 128, "LOOPBACK"),
        v6(listOf(0, 0, 0, 0, 0, 0xffff), 96, "IPV4_MAPPED"),
        v6(emptyList(), 96, "IPV4_COMPATIBLE"),
        v6(listOf(0x64, 0xff9b), 96, "NAT64"),
        v6(listOf(0x100), 64, "DISCARD"),
        v6(listOf(0x2001, 0xdb8), 32, "DOCUMENTATION"),
        // 검토 #2 n-2(IANA IPv6 special-purpose registry): benchmarking·ORCHID(폐기)·ORCHIDv2(overlay 식별자 — 레지스트리상 전역 True지만 HTTP 목적지 아님)·문서용(3fff::/20, 2024).
        v6(listOf(0x2001, 0x2, 0), 48, "BENCHMARK"),
        v6(listOf(0x2001, 0x10), 28, "ORCHID"),
        v6(listOf(0x2001, 0x20), 28, "ORCHID_V2"),
        v6(listOf(0x3fff), 20, "DOCUMENTATION"),
        v6(listOf(0x2001), 32, "TEREDO"),
        v6(listOf(0x2002), 16, "RELAY_6TO4"),
        v6(listOf(0xfc00), 7, "UNIQUE_LOCAL"),
        v6(listOf(0xfe80), 10, "LINK_LOCAL"),
        v6(listOf(0xfec0), 10, "SITE_LOCAL"),
        v6(listOf(0xff00), 8, "MULTICAST"),
    )

    private val V6_GLOBAL_UNICAST = v6(listOf(0x2000), 3, "GLOBAL")

    fun classify(address: ByteArray): WebhookAddressClass = when (address.size) {
        4 -> V4_BLOCKED.firstOrNull { it.contains(address) }?.let { WebhookAddressClass.Blocked(it.reason) } ?: WebhookAddressClass.Public
        16 -> V6_BLOCKED.firstOrNull { it.contains(address) }?.let { WebhookAddressClass.Blocked(it.reason) }
            ?: if (V6_GLOBAL_UNICAST.contains(address)) WebhookAddressClass.Public else WebhookAddressClass.Blocked("NOT_GLOBAL_UNICAST")
        else -> WebhookAddressClass.Blocked("MALFORMED")
    }

    /** 해석된 모든 주소가 공인이어야 한다(하나라도 차단이면 그 이유로 차단, 빈 해석은 차단). */
    fun classifyAll(addresses: List<ByteArray>): WebhookAddressClass {
        if (addresses.isEmpty()) return WebhookAddressClass.Blocked("NO_ADDRESS")
        return addresses.map(::classify).firstOrNull { it is WebhookAddressClass.Blocked } ?: WebhookAddressClass.Public
    }
}
