import type { TransactionMerchant } from '../api/adminApi'
import { labelOf } from './labels'

/**
 * 거래 목록 가맹점·결제수단 표시(TXN-LIST-ENRICH-001 §3.4 — D-ADMIN-UI-004 ③⑤, D-MCODE-003). 모든 값은 React 텍스트 노드로만 그린다(HTML로 해석하지 않는다).
 * - 가맹점: "<상호> · <가맹점 코드>"(내부 키 `mer_`·`biz_`는 응답에 없다). 라벨이 없으면 [MERCHANT_LABEL_MISSING], 쪽 전체가 UNAVAILABLE이면 칸은 MID만 + 표 아래 [MERCHANT_LABELS_UNAVAILABLE] 1줄.
 * - 결제수단: `labelOf`(CARD → "카드" — CARD = 신용 + 체크라 "신용카드"로 쓰지 않는다, Q-TXENRICH-1), 모르는 코드는 원문.
 */
export const MERCHANT_LABEL_MISSING = '가맹점 정보 없음'
export const MERCHANT_LABELS_UNAVAILABLE = '가맹점 정보를 불러오지 못했어요'

/** 라벨 없음은 `null`(OK + MASTER에 없음/UNAVAILABLE)과 `undefined`(키 자체가 없는 응답 — 예: 상세 타입의 선택 필드)를 같게 본다(리뷰 NIT-3). */
export function merchantLabelText(merchant: TransactionMerchant | null | undefined): string | null {
  return merchant == null ? null : `${merchant.name} · ${merchant.code}`
}

export function environmentMid(environment: string, merchantAccountId: string): string {
  return `${environment} · ${merchantAccountId}`
}

export function paymentMethodLabel(code: string): string {
  return labelOf(code)
}
