import type { MerchantDetail, MerchantHistoryEntry } from '../api/adminApi'
import { merchantLabelText } from './merchantLabel'
import { displayRegistrationNumber } from './registrationNumber'
import { historyTargetValue } from './merchantLabels'

/**
 * 가맹점 변경 이력 "대상" 열(ADMIN-HISTORY-KEYS-001 §3 — D-ADMIN-UI-004 ③, D-MCODE-003).
 * 내부 키 `mer_`·`biz_`는 화면에 그리지 않는다(응답에는 남는다 — D-BIZLOOKUP-001과 같은 원칙).
 * 이름은 MASTER `JooqMerchantHistoryReader`가 읽는 집계 13종. `PolicyVersion`은 가맹점 이력에서 제외되어 넣지 않는다.
 * `CODE_LABELS`(서버 code 집합 시험 대상)와 분리한 이력 전용 맵이다(리뷰 MAJOR-1).
 */
export const HISTORY_TARGET_LABELS: Readonly<Record<string, string>> = {
  Business: '사업자',
  Merchant: '가맹점',
  MerchantSite: '사이트',
  Contract: '계약',
  ContractServiceAgreement: '서비스 약정(CSA)',
  MerchantAccount: 'MID',
  SiteAssignment: '사이트 배정',
  PaymentMethodActivation: '결제수단 개통',
  SiteActivationConfirmation: '사이트 적용 확인',
  CurrencyEnablement: '통화 활성화',
  PolicyAssignment: '정책 배정',
  TransactionLimitPolicy: '거래 한도',
  CardRouteActivation: '카드사 Route 개통',
}

/** 집계 종류의 한국어 이름. 모르는 종류는 원래 코드 그대로. */
export function historyTargetLabel(aggregateType: string): string {
  return Object.prototype.hasOwnProperty.call(HISTORY_TARGET_LABELS, aggregateType) ? HISTORY_TARGET_LABELS[aggregateType]! : aggregateType
}

/** 내부 키가 보이지 않을 때 쓰는 표시(fail-closed). */
export const HISTORY_TARGET_HIDDEN = '—'

/**
 * 대상 식별 표시. 가맹점 자신 = "<상호> · <가맹점 코드>", 그 가맹점의 사업자 = "<상호> · <사업자등록번호>"(원문 하이픈 — BRN-UNMASK-001·D-BIZ-007·D-PII-001 ①).
 * 그 밖에 `mer_`/`biz_`가 들어 있는 값은 [HISTORY_TARGET_HIDDEN](정상 경로에서는 없음 — 방어).
 * MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③): 나머지(Site·계약·서비스 약정·배정·개통·적용 확인·통화·Route·한도·정책 배정)는 원래 식별자(site_·ctr_·csa_·asg_·act_·sac_·cur_·cra_·tlp_·pas_)
 * 대신 상세 응답 안의 사람용 값([historyTargetValue]) — 찾지 못하면 빈 문자열(종류 이름만 보인다). MID는 외부 식별자라 그대로.
 */
export function historyTarget(entry: MerchantHistoryEntry, detail: MerchantDetail): string {
  if (entry.aggregateType === 'Merchant' && entry.aggregateId === detail.merchant.merchantId) {
    return merchantLabelText({ name: detail.merchant.name, code: detail.merchant.merchantCode }) ?? HISTORY_TARGET_HIDDEN
  }
  if (entry.aggregateType === 'Business' && entry.aggregateId === detail.business.businessId) {
    return `${detail.business.name} · ${displayRegistrationNumber(detail.business.registrationNumber)}`
  }
  if (entry.aggregateId.includes('mer_') || entry.aggregateId.includes('biz_')) return HISTORY_TARGET_HIDDEN
  return historyTargetValue(entry.aggregateType, entry.aggregateId, detail)
}
