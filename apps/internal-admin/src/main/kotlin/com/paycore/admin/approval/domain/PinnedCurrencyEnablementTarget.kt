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
    /**
     * MERCHANT-CODE-001 MC-3a(§5.3·§5.6.4 — C-19): 요청 시점 가맹점 코드(**표시용**). 새 행 생성 시 필수(요청 경로가 MASTER 상세 코드를 넣는다), 저장된 기존 행 읽기에서는 선택
     * (MC-3a 전 PENDING·회복 대상 행이 그대로 해석돼야 한다). A 단계 비교에서 뺀다 — 코드 변경 때문에 승인이 INVALIDATED되지 않게.
     */
    val merchantCode: String? = null,
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
        require(merchantCode == null || ApprovalTarget.Merchant.CODE.matches(merchantCode)) { "merchant code format (§3.1)" }
    }

    fun sameTargetAs(current: PinnedCurrencyEnablementTarget): Boolean = copy(merchantName = current.merchantName, merchantCode = current.merchantCode) == current

    override fun toSnapshotJson(): String =
        "{\"merchantId\":\"$merchantId\",\"merchantName\":${PinnedContractApprovalTarget.quote(merchantName)}${codeJson()},\"merchantAccountId\":\"$merchantAccountId\"," +
            "\"accountLifecycle\":\"$accountLifecycle\",\"currency\":\"$currency\",\"effectiveFromMode\":\"$effectiveFromMode\"," +
            "\"effectiveFrom\":${date(effectiveFrom)},\"effectiveTo\":${date(effectiveTo)}}"

    private fun date(value: LocalDate?): String = value?.let { "\"$it\"" } ?: "null"

    /** 코드 키는 값이 있을 때만(MC-3a 전 행의 결정적 JSON은 그대로 — 기존 행 바이트 불변). */
    private fun codeJson(): String = merchantCode?.let { ",\"merchantCode\":\"$it\"" } ?: ""

    companion object {
        const val AT_EXECUTION = "AT_EXECUTION"
        const val DATE = "DATE"
        val MODES = setOf(AT_EXECUTION, DATE)

        /** C-41: 관리 채널 통화 활성화는 KRW만(MASTER parser와 같은 목록 복제 — 실제 통화 범위 추가안은 사용자 채택 전). */
        val CURRENCIES = setOf("KRW")
    }
}
