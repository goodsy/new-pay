package com.paycore.master.merchant.domain

/**
 * 계약 사람용 코드(SITE-CONTRACT-CODE-001 §3.1 — D-ADMIN-UI-005, Q-SC-1·2·3·5): `ctr-<발급 당시 가맹점 코드>-<NN>`. 내부 키 `ctr_<ULID>`와 구별, 정규화 없음, 불변·재사용 금지, 전체 유일.
 * 서비스 계약(csa_)에는 코드가 없다(Q-SC-6). Revision 번호 체계는 그대로(C-10).
 *
 * C-12: `Contract` Aggregate 필드가 아니다(등록 경로 발급·저장, Reader·재생 조회만 읽음 — `contracts.lock/find·update`는 코드 표 무관). 순수 Kotlin.
 */
class ContractCode private constructor(val issuedMerchantCode: MerchantCode, val seq: Int) {
    val value: String = "$PREFIX-${issuedMerchantCode.value}-${HumanCodeSequence.text(seq)}"

    override fun equals(other: Any?): Boolean = other is ContractCode && other.value == value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        const val PREFIX = "ctr"

        /** binary 비교 — DB CHECK `REGEXP_LIKE(contract_code, …, 'c')`와 같은 문법. */
        val FORMAT = Regex("^ctr-[a-z][a-z0-9]{5,13}-${HumanCodeSequence.PATTERN}$")

        /** 등록 판정(실행기 `beginWrites` 전): 9999 초과는 저장된 업무 거절 `CONTRACT_CODE_EXHAUSTED`(C-1), 1 미만은 호출 결함. */
        fun issue(merchantCode: MerchantCode, nextSeq: Int): ContractCode {
            require(nextSeq >= HumanCodeSequence.MIN) { "next code sequence starts at ${HumanCodeSequence.MIN}" }
            if (nextSeq > HumanCodeSequence.MAX) throw MasterRuleViolation(MasterRejection.CONTRACT_CODE_EXHAUSTED)
            return ContractCode(merchantCode, nextSeq)
        }

        /** 저장된 값 복원(형식 위반이면 IllegalArgument). */
        fun parse(value: String): ContractCode {
            require(FORMAT.matches(value)) { "contractCode must match ctr-<merchantCode>-<NN>" }
            val (merchantCode, seq) = HumanCodeSequence.split(value, PREFIX)
            return ContractCode(merchantCode, seq)
        }
    }
}
