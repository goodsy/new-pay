package com.paycore.admin.account.domain

/** 복구 회차 근거 문자열(§4.9.2): 대기 근거·자격 근거·역할 정지 원인 = `episode:<회차 번호>`. */
object RecoveryBasis {
    private const val PREFIX = "episode:"

    fun of(episodeNo: Long): String {
        require(episodeNo > 0) { "recovery episode numbers start at 1" }
        return PREFIX + episodeNo
    }

    fun episodeOf(basis: String): Long? = basis.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.toLongOrNull()
}
