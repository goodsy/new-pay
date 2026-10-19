package com.paycore.simulator.van.adapter.integration

/**
 * 프레임 HMAC 키 목록(keyId → PG client·키). 시험·Demo 키만(환경변수 — 저장소에 운영 키 없음, AUTH-001 §5.6 겹 6). 모르는 keyId의 프레임은 INTEGRITY다.
 * 키 원문은 toString·로그에 나오지 않는다.
 */
class VanKeyRing(private val keys: Map<String, Key>) {
    init {
        require(keys.keys.all { KEY_ID.matches(it) }) { "key ids must be 1..64 printable ASCII without spaces" }
        require(keys.values.all { it.secret.size >= MIN_KEY_BYTES }) { "HMAC keys must be at least $MIN_KEY_BYTES bytes" }
    }

    fun find(keyId: String): Key? = keys[keyId]

    override fun toString(): String = "VanKeyRing(keyIds=${keys.keys})"

    class Key(val clientId: String, val secret: ByteArray) {
        override fun toString(): String = "Key(clientId=$clientId)"
    }

    companion object {
        const val MIN_KEY_BYTES = 32
        val KEY_ID = Regex("^[!-~]{1,64}$")
    }
}
