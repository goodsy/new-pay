package com.paycore.master.merchant.domain

/**
 * 사업자 계약 명령 결과(MERCHANT-FLOW-001 §4B.2): 명령 뒤 Aggregate 상태([contract])와 **그 명령이 새로 만든 행만**. 저장 adapter(`JpaBusinessContractRepository.insert`)는
 * 이것만 INSERT한다(UPDATE 없음 — 버전·닫기·사건 표는 INSERT 전용).
 * - 신규 계약 완료: [newContract] = true, [version] = 버전 1(범위 행 포함).
 * - 변경 계약 완료: [version] = 새 버전 + [close] = 직전 버전 닫기.
 * - 정지·정지 해제·해지: [event] 하나.
 * 효력 없음(BR-BIZ-313·321): 권리·Snapshot·Outbox를 만들지 않는다.
 */
data class BusinessContractChange(
    val contract: BusinessContract,
    val newContract: Boolean,
    val version: BusinessContractVersion?,
    val close: BusinessContractVersionClose?,
    val event: BusinessContractEvent?,
) {
    init {
        when {
            newContract -> require(version?.versionNo == 1 && close == null && event == null) { "a new contract carries version 1 only" }
            version != null -> require(close?.closedByVersionNo == version.versionNo && event == null) { "a new version carries the close of the previous one" }
            else -> require(event != null && close == null) { "a change creates a contract, a version or an event" }
        }
    }
}
