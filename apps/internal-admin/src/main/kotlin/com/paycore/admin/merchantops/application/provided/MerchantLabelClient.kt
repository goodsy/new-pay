package com.paycore.admin.merchantops.application.provided

/**
 * 거래 목록·상세 표시용 MID → 가맹점 라벨 묶음 조회(TXN-LIST-ENRICH-001 §4.2·§5 — MASTER 내부 API `merchant-account-labels`, 같은 서명 채널·**라벨 전용** permit·짧은 read timeout §3.6).
 * MERCHANT-CODE-001 §5.6.3(MC-3b): 승인·감사 화면 표시용 가맹점 내부 키 → 라벨 묶음 조회 [merchantLabels](MASTER `merchant-labels`, 같은 permit·timeout·상한).
 * 사용처는 paymentops `TransactionLookupService`와 merchantops application(화면 라벨 API Use Case)뿐(ArchUnit). 판정에 쓰지 않는 보조 표시값 — 실패는 예외가 아니라
 * [MerchantLabelResult.Unavailable](목록은 그대로, fail-open). **DB TX 안에서 부르면 `IllegalStateException`**. 라벨 값(상호·코드)에 내부 키(`mer_`·`biz_`)가 없다(D-MCODE-003).
 */
interface MerchantLabelClient {
    /** [merchantAccountIds]: [MerchantAccountIdRules.FORMAT]에 맞고 서로 다른 1..[MerchantAccountIdRules.MAX_PER_CALL]개, 오름차순(호출자가 만든다 — 위반 = `IllegalArgumentException`). */
    fun labels(merchantAccountIds: List<String>): MerchantLabelResult

    /**
     * [merchantIds]: [MerchantIdRules.FORMAT]에 맞고 서로 다른 1..[MerchantIdRules.MAX_PER_CALL]개, 오름차순(위반 = `IllegalArgumentException`). [MerchantLabelResult.Found.labels]의
     * 키는 merchantId(호출자 대응용 — 화면에 그리지 않는다).
     */
    fun merchantLabels(merchantIds: List<String>): MerchantLabelResult
}
