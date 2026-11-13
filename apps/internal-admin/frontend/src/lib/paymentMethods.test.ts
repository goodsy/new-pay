// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { PAYMENT_METHODS, SERVICE_KINDS, serviceKindLabel } from './paymentMethods'

/** ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ⑤ "구조만 확장 가능하게"): 목록 구조이되 지금은 서버가 받는 값 하나씩뿐 — 항목 추가는 정책 결정이라 이 시험이 막는다. */
describe('payment method / service kind lists (structure only — no new method adopted)', () => {
  it('holds only the values the server accepts today', () => {
    expect(PAYMENT_METHODS).toEqual([{ code: 'CARD', label: '카드' }])
    expect(SERVICE_KINDS).toEqual([{ code: 'CARD_PG_PAYMENT', label: '카드 PG 결제' }])
    expect(serviceKindLabel('CARD_PG_PAYMENT')).toBe('카드 PG 결제')
    expect(serviceKindLabel('UNKNOWN_KIND')).toBe('UNKNOWN_KIND')
  })
})
