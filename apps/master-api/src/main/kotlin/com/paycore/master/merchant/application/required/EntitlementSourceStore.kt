package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.EntitlementSource
import com.paycore.master.merchant.domain.MerchantId

/** Snapshot 조립 입력 읽기(같은 MASTER TX·같은 JPA 영속성 컨텍스트 — 명세 §4.6). MID가 없는 Merchant는 null(발행 대상 아님, §3.2). */
interface EntitlementSourceStore {
    fun load(merchantId: MerchantId): EntitlementSource?
}
