package com.paycore.admin.security

import java.net.Inet4Address
import java.net.InetAddress

/**
 * IP 허용 목록(§4.8.1): 정확한 IP 또는 CIDR. 숫자 리터럴만 받는다(호스트 이름 거부 — DNS 조회 없음). 빈 목록은 거부(기동 실패).
 * IPv4-mapped IPv6(`::ffff:a.b.c.d`)는 IPv4로 정규화해 비교한다(JDK `InetAddress`가 mapped 리터럴을 `Inet4Address`로 돌려준다).
 */
class IpAllowlist private constructor(private val entries: List<Entry>) {

    /** LIVE에서 거부할 설정(S4 리뷰 m-5): 모든 주소 허용(접두 0 — `0.0.0.0/0`·`::/0`). */
    fun liveViolations(): List<String> = if (entries.any { it.prefix == 0 }) listOf("paycore.admin.network.ip-allowlist permits every address") else emptyList()

    fun allows(remoteAddr: String): Boolean {
        val address = literal(remoteAddr) ?: return false
        return entries.any { it.matches(address) }
    }

    private class Entry(private val network: ByteArray, val prefix: Int) {
        fun matches(address: InetAddress): Boolean {
            val bytes = address.address
            if (bytes.size != network.size) return false
            val full = prefix / 8
            for (i in 0 until full) if (bytes[i] != network[i]) return false
            val rest = prefix % 8
            if (rest == 0) return true
            val mask = (0xFF shl (8 - rest)) and 0xFF
            return (bytes[full].toInt() and mask) == (network[full].toInt() and mask)
        }
    }

    companion object {
        private val LITERAL = Regex("^[0-9A-Fa-f:.]+$")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        fun parse(values: List<String>): IpAllowlist {
            val entries = values.map(String::trim).filter(String::isNotEmpty).mapIndexed { index, value ->
                val address = value.substringBefore('/')
                val parsed = literal(address) ?: throw IllegalArgumentException("ip allowlist entries are IP literals or CIDR (entry ${index + 1})")
                val max = if (parsed is Inet4Address) 32 else 128
                val prefix = if (value.contains('/')) value.substringAfter('/').toIntOrNull() else max
                require(prefix != null && prefix in 0..max) { "ip allowlist prefix must be 0-$max" }
                Entry(parsed.address, prefix)
            }
            require(entries.isNotEmpty()) { "paycore.admin.network.ip-allowlist must not be empty" }
            return IpAllowlist(entries)
        }

        /**
         * `InetAddress`에 넘겨도 이름 해석(DNS)이 일어나지 않는 숫자 리터럴인가(S4 리뷰 nit): IPv4는 네 octet 모두 0~255, 그 밖은 `:`가 있는 16진 IPv6 형태.
         * 범위를 벗어난 IPv4 모양(예 `999.1.1.1`)은 JDK가 호스트 이름으로 조회하려 하므로 여기서 거른다.
         */
        internal fun isNumericLiteral(value: String): Boolean {
            if (!LITERAL.matches(value)) return false
            if (value.contains(':')) return true
            return IPV4.matches(value) && value.split('.').all { it.toInt() <= 255 }
        }

        private fun literal(value: String): InetAddress? =
            if (isNumericLiteral(value)) runCatching { InetAddress.getByName(value) }.getOrNull() else null
    }
}
