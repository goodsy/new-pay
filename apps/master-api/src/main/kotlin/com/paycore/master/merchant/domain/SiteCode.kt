package com.paycore.master.merchant.domain

/**
 * Site 사람용 코드(SITE-CONTRACT-CODE-001 §3.1 — D-ADMIN-UI-005, Q-SC-1·2·3·5): `site-<발급 당시 가맹점 코드>-<NN>`. 소문자·하이픈 — 내부 키 `site_<ULID>`와 판독·정규식 모두 구별된다.
 * **정규화 없음**(D-CP-001): 대문자·공백·밑줄은 형식 위반. 발급 후 불변·재사용 금지(DB 권한 SELECT, INSERT), MASTER 전체에서 하나(DB UNIQUE).
 *
 * C-12: 이 값은 `MerchantSite` Aggregate의 필드가 **아니다** — 등록 경로가 [issue]로 만들어 저장 Port로 따로 저장하고, 읽기는 Reader·재생 조회만 한다. 판정 재료로 쓰지 않는다.
 * 순수 Kotlin(Spring·JPA·jOOQ 비의존). 메시지에 입력 값을 넣지 않는다.
 */
class SiteCode private constructor(val issuedMerchantCode: MerchantCode, val seq: Int) {
    val value: String = "$PREFIX-${issuedMerchantCode.value}-${HumanCodeSequence.text(seq)}"

    override fun equals(other: Any?): Boolean = other is SiteCode && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val PREFIX = "site"

        /** binary 비교 — DB CHECK `REGEXP_LIKE(site_code, …, 'c')`와 같은 문법. */
        val FORMAT = Regex("^site-[a-z][a-z0-9]{5,13}-${HumanCodeSequence.PATTERN}$")

        /**
         * 등록 판정(실행기 `beginWrites` 전): [nextSeq] = 그 가맹점 Site 코드 `MAX(code_seq) + 1`(merchant 잠금 뒤 읽은 값), [merchantCode] = 잠근 Merchant의 현재 코드.
         * 9999 초과는 저장된 업무 거절 `SITE_CODE_EXHAUSTED`(C-1), 1 미만은 호출 결함.
         */
        fun issue(merchantCode: MerchantCode, nextSeq: Int): SiteCode {
            require(nextSeq >= HumanCodeSequence.MIN) { "next code sequence starts at ${HumanCodeSequence.MIN}" }
            if (nextSeq > HumanCodeSequence.MAX) throw MasterRuleViolation(MasterRejection.SITE_CODE_EXHAUSTED)
            return SiteCode(merchantCode, nextSeq)
        }

        /** 저장된 값 복원(형식 위반이면 IllegalArgument). */
        fun parse(value: String): SiteCode {
            require(FORMAT.matches(value)) { "siteCode must match site-<merchantCode>-<NN>" }
            val (merchantCode, seq) = HumanCodeSequence.split(value, PREFIX)
            return SiteCode(merchantCode, seq)
        }
    }
}
