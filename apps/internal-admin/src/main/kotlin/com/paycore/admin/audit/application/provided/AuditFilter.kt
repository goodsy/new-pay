package com.paycore.admin.audit.application.provided

import java.time.Instant

/** 감사 검색 조건(모두 선택). 시각은 [from] 이상 [to] 미만(UTC). [beforeEventId]는 keyset 페이지(그 ID보다 작은 행), 최신순. */
data class AuditFilter(
    val actorId: String? = null,
    val targetActorId: String? = null,
    val action: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val beforeEventId: Long? = null,
    val limit: Int = DEFAULT_LIMIT,
) {
    init {
        require(limit in 1..MAX_LIMIT) { "audit page size must be 1-$MAX_LIMIT" }
        require(action == null || Regex("^[A-Z][A-Z0-9_]{0,47}$").matches(action)) { "audit action filter is a fixed code" }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
