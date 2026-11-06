package com.paycore.simulator.acquirer.domain

import java.security.MessageDigest
import java.util.HexFormat

/** SHA-256 소문자 hex(파일 계약 v1의 봉인 hash·bodySha256·ACK/결과 hash 표기). */
object Sha256 {
    private val HEX = Regex("^[0-9a-f]{64}$")

    fun hex(bytes: ByteArray): String = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun isHex(value: String): Boolean = HEX.matches(value)
}
