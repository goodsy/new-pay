package com.paycore.admin.merchantops.application.provided

/**
 * 거래 목록·상세 표시용 MID → 가맹점 라벨 묶음 조회(TXN-LIST-ENRICH-001 §4.2·§5 — MASTER 내부 API `merchant-account-labels`, 같은 서명 채널·**라벨 전용** permit·짧은 read timeout §3.6).
 * 사용처는 paymentops `TransactionLookupService`만(ArchUnit). 판정에 쓰지 않는 보조 표시값 — 실패는 예외가 아니라 [MerchantLabelResult.Unavailable](목록은 그대로, fail-open).
 * **DB TX 안에서 부르면 `IllegalStateException`**. 결과에 내부 키(`mer_`·`biz_`)가 없다(D-MCODE-003).
 */
interface MerchantLabelClient {
    /** [merchantAccountIds]: [MerchantAccountIdRules.FORMAT]에 맞고 서로 다른 1..[MerchantAccountIdRules.MAX_PER_CALL]개, 오름차순(호출자가 만든다 — 위반 = `IllegalArgumentException`). */
    fun labels(merchantAccountIds: List<String>): MerchantLabelResult
}
