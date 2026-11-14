package com.paycore.admin.approval.domain

import java.time.LocalDate

/**
 * 통화 활성화 승인 요청 시점의 고정 대상(MERCHANT-OPS-002 B3-2 §9.16·§9.26 pinned — 표시·비교용): `{merchantId, merchantName, merchantAccountId, accountLifecycle, currency,
 * effectiveFromMode, effectiveFrom(DATE만 — KST 날짜), effectiveTo(KST 날짜, 선택 — C-7 배타 끝)}`. 근거 참조 없음.
 * [effectiveFromMode] `AT_EXECUTION`이면 [effectiveFrom] = null(승인 화면 "효력 시작: 승인 실행 즉시(MASTER 실행 시각으로 확정)"), `DATE`면 KST 날짜(화면 "YYYY-MM-DD 00:00 KST부터").
 * 날짜를 KST 날짜 그대로 두고 MASTER 본문의 UTC 시각 변환은 승인 TX1이 한다(C-7). 등록자 필드 없음 — 통화 활성화에는 1인 등록 단계가 없다(§9.27 적용 범위 밖, 요청자≠승인자만).
 * A 단계 재조회 값(MID 생애 + 같은 요청 값)과 다르면 INVALIDATED(TARGET_CHANGED). JSON은 키를 늘 쓴다(없는 값은 `null`). 표시 이름은 비교하지 않는다(C-16).
 */
data class PinnedCurrencyEnablementTarget(
    override val merchantId: String,
    val merchantName: String,
    val merchantAccountId: String,
    val accountLifecycle: String,
    val currency: String,
    val effectiveFromMode: String,
    val effectiveFrom: LocalDate?,
    val effectiveTo: LocalDate?,
) : PinnedMerchantTarget {
    init {
        require(ApprovalTarget.Merchant.MERCHANT_ID.matches(merchantId)) { "merchant id format" }
        require(PinnedContractApprovalTarget.NAME.matches(merchantName)) { "merchant name" }
        require(PinnedActivationApprovalTarget.MERCHANT_ACCOUNT_ID.matches(merchantAccountId)) { "merchant account id format" }
        require(PinnedActivationApprovalTarget.CODE.matches(accountLifecycle)) { "account lifecycle code" }
        require(currency in CURRENCIES) { "currency (C-41 — KRW only)" }
        require(effectiveFromMode in MODES) { "effective start mode" }
        require((effectiveFromMode == DATE) == (effectiveFrom != null)) { "a start date exactly in the DATE mode" }
        require(effectiveTo == null || effectiveFrom == null || effectiveTo.isAfter(effectiveFrom)) { "end after start" }
    }

    fun sameTargetAs(current: PinnedCurrencyEnablementTarget): Boolean = copy(merchantName = current.merchantName) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)},\"merchantAccountId\":\"$merchantAccountId\"," +
            "\"accountLifecycle\":\"$accountLifecycle\",\"currency\":\"$currency\",\"effectiveFromMode\":\"$effectiveFromMode\"," +
            "\"effectiveFrom\":${date(effectiveFrom)},\"effectiveTo\":${date(effectiveTo)}}"

    private fun date(value: LocalDate?): String = value?.let { "\"$it\"" } ?: "null"

    companion object {
        const val AT_EXECUTION = "AT_EXECUTION"
        const val DATE = "DATE"
        val MODES = setOf(AT_EXECUTION, DATE)

        /** C-41: 관리 채널 통화 활성화는 KRW만(MASTER parser와 같은 목록 복제 — 실제 통화 범위 추가안은 사용자 채택 전). */
        val CURRENCIES = setOf("KRW")
    }
}
