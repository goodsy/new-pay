package com.paycore.admin.merchantops.adapter.integration

import com.paycore.admin.merchantops.application.required.BusinessReadLimits
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 사업자 관리 설정(`paycore.admin.merchant-ops` — BUSINESS-PROFILE-001 §7.1 m-2, **Demo 값, 운영값 아님** — LIVE Gate L-14). 기본값 없음: 키가 빠지거나 범위 밖이면 기동 실패.
 * - [businessReadsPerMinute](`business-reads-per-minute`): 사업자 조회 창(목록·상세·이력·번호 조회 공용, actor별 분당 1..[MAX_READS]). Demo 30.
 * 같은 접두어의 `approval` 블록은 `MerchantApprovalSettings`가 따로 읽는다(이 클래스는 그 키를 모른다).
 */
@ConfigurationProperties(prefix = "paycore.admin.merchant-ops")
class BusinessOpsSettings(businessReadsPerMinute: Int?) : BusinessReadLimits {
    override val businessReadsPerMinute: Int = requireNotNull(businessReadsPerMinute) { "paycore.admin.merchant-ops.business-reads-per-minute is required" }

    init {
        require(this.businessReadsPerMinute in 1..MAX_READS) { "paycore.admin.merchant-ops.business-reads-per-minute 1..$MAX_READS" }
    }

    override fun toString(): String = "BusinessOpsSettings(businessReadsPerMinute=$businessReadsPerMinute)"

    companion object {
        /** 상한(기존 조회 창 상한과 같은 자릿수 — `MasterOpsSettings` MAX_RATE). */
        const val MAX_READS = 10_000
    }
}
