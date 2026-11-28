package com.paycore.master.merchant.application.required

import com.paycore.master.merchant.domain.BusinessContractMerchantHistory
import com.paycore.master.merchant.domain.BusinessId

/**
 * 계약·가맹점 이력 판정 입력(BIZ-CRIT-APPROVAL-001 §3.6 — BR-BIZ-386, D-GOV-026 ②). **사업자 행 잠금 뒤 같은 TX**에서 부른다(사업자 계약 생성·가맹점 등록도 사업자를 먼저 잠그므로 직렬화).
 * 사업자 계약(상태 무관 행 존재)·가맹점(종료 포함 행 존재)만 본다 — 계약 접수 건은 보지 않는다. jOOQ Reader가 아니라 JPA 스칼라 질의(판정 입력 — Reader 판정 금지).
 *
 * 별도 Port인 이유 [구현 관례]: `BusinessContractRepository`를 쓰는 서비스는 Outbox·`Work.record`를 쓰지 않는다(FLOW-2a §4B.12 ② ArchUnit) — 사업자 수정 서비스는 Outbox를 쓰므로
 * 계약 저장 Port에 의존하지 않고 존재 여부만 이 Port로 읽는다.
 */
interface BusinessContractMerchantHistoryLookup {
    fun of(businessId: BusinessId): BusinessContractMerchantHistory
}
