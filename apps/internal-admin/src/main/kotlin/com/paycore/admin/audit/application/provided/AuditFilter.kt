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
    /**
     * MERCHANT-CODE-001 MC-3a(§5.6.4 — C-30): 가맹점 내부 키(mer_) — `target_merchant_key = ?`(새 행 target_merchant_id, MC-3a 전 행 target_ref = mer_ — 옛 코드·새 코드·mer_ 행이 한 키).
     * ADMIN은 이 열로 코드를 해석하지 않는다(코드 → 내부 키 해석은 MASTER 코드 검색).
     */
    val targetMerchantId: String? = null,
) {
    init {
        require(targetMerchantId == null || Regex("^mer_[0-9A-HJKMNP-TV-Z]{26}$").matches(targetMerchantId)) { "merchant key filter is mer_ + ULID" }
        require(limit in 1..MAX_LIMIT) { "audit page size must be 1-$MAX_LIMIT" }
        require(action == null || Regex("^[A-Z][A-Z0-9_]{0,47}$").matches(action)) { "audit action filter is a fixed code" }
    }

    companion object {
        const val DEFAULT_LIMIT = 50
        const val MAX_LIMIT = 200
    }
}
