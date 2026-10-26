package com.paycore.admin.account.domain

/** 화면·감사·승인 스냅샷에 쓰는 표시명. 1~100자, 앞뒤 공백·제어 문자 없음(감사 행·화면 깨짐 방지). */
@JvmInline
value class DisplayName(val value: String) {
    init {
        require(value.isNotEmpty() && value.length <= MAX_LENGTH) { "display name must be 1-$MAX_LENGTH characters" }
        require(value == value.trim()) { "display name must not have leading or trailing whitespace" }
        require(value.none { it.isISOControl() }) { "display name must not contain control characters" }
    }

    override fun toString(): String = value

    private companion object {
        const val MAX_LENGTH = 100
    }
}
