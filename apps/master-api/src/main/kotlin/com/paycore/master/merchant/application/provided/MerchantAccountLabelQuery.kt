package com.paycore.master.merchant.application.provided

import com.paycore.master.merchant.domain.MerchantAccountId

/**
 * 거래 목록 표시용 MID → 가맹점 라벨 묶음 조회(TXN-LIST-ENRICH-001 §4.2 — CQRS 조회 측, jOOQ `adapter.query`, 읽기 전용·REPEATABLE READ 한 문장). 판정용 권위 조회가 아니다.
 * 진입점은 ADMIN 채널 `MerchantAccountLabelController`만(ArchUnit). 결과에는 MID·상호·**현재** 가맹점 코드만 — 내부 키(`mer_`·`biz_`)·사업자 정보·lifecycle·기관 참조는 어떤 타입에도 없다
 * (D-MCODE-003). 없는 MID는 결과에서 빠지고 TERMINATED 가맹점도 포함한다. 결과는 MID 오름차순.
 */
interface MerchantAccountLabelQuery {
    /** [merchantAccountIds] 1..[MERCHANT_ACCOUNT_LABELS_MAX]개, 중복 없음(위반 = `IllegalArgumentException` — 진입 해석기가 먼저 400으로 거른다). */
    fun labels(merchantAccountIds: List<MerchantAccountId>): List<MerchantAccountLabel>
}

/** 한 번에 조회할 수 있는 MID 수 상한(§3.2 — ADMIN 목록 한 쪽 상한과 같다). */
const val MERCHANT_ACCOUNT_LABELS_MAX = 50

/** MID 라벨(표시 전용 — 상호는 `merchant.display_name`, 코드는 가장 큰 배정 seq). */
data class MerchantAccountLabel(val merchantAccountId: String, val merchantName: String, val merchantCode: String) {
    override fun toString(): String = "MerchantAccountLabel(***)"
}
