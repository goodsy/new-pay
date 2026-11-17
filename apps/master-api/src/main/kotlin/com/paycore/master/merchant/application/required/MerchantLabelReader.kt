package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantLabel

/**
 * 가맹점 라벨 읽기 Port(MERCHANT-CODE-001 §5.6.3 MC-3b — CQRS 조회 측). 구현은 jOOQ `adapter.query.JooqMerchantLabelReader`(읽기 전용 REPEATABLE READ, 한 문장, 쓰기 금지 — ArchUnit).
 * 결과는 merchantId 오름차순, 없는 키는 빠진다. 코드 배정이 없는 가맹점은 결함 — 예외(빈 값으로 내보내지 않는다).
 */
interface MerchantLabelReader {
    fun labels(merchantIds: List<String>): List<MerchantLabel>
}
