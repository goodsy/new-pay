package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.application.provided.MerchantAccountLabel

/**
 * MID 라벨 읽기 Port(TXN-LIST-ENRICH-001 §4.2 — CQRS 조회 측). 구현은 jOOQ `adapter.query.JooqMerchantAccountLabelReader`(읽기 전용 REPEATABLE READ, 한 문장, 쓰기 금지 — ArchUnit).
 * 결과는 MID 오름차순, 없는 MID는 빠진다. 코드 배정이 없는 가맹점은 결함 — 예외(빈 값으로 내보내지 않는다).
 */
interface MerchantAccountLabelReader {
    fun labels(merchantAccountIds: List<String>): List<MerchantAccountLabel>
}
