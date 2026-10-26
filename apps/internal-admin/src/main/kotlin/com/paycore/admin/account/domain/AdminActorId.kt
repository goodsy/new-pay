package com.paycore.admin.account.domain

/**
 * 불변 관리자 식별자(D-ADMIN-004, §4.5.2): `adm_` + ULID 26자(Crockford base32 대문자). 생성 뒤 바뀌지 않고 재사용하지 않는다
 * (계정 DELETE 권한 없음 — runtime 1142). 감사·승인 행은 이 ID와 기록 시점 표시명 스냅샷을 함께 남긴다.
 */
@JvmInline
value class AdminActorId(val value: String) : Comparable<AdminActorId> {
    init {
        require(FORMAT.matches(value)) { "admin actor id must be 'adm_' + 26 Crockford base32 characters" }
    }

    override fun compareTo(other: AdminActorId): Int = value.compareTo(other.value)

    override fun toString(): String = value

    companion object {
        const val PREFIX = "adm_"
        private val FORMAT = Regex("^adm_[0-9A-HJKMNP-TV-Z]{26}$")
    }
}
