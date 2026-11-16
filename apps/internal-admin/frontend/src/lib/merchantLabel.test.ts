import { describe, expect, it } from 'vitest'
import { environmentMid, merchantLabelText, paymentMethodLabel } from './merchantLabel'

/** TXN-LIST-ENRICH-001 §3.4 표시 규칙 — 리뷰 NIT-3: 라벨 키가 없는 응답(`undefined`)도 `null`과 같이 "라벨 없음"이다. */
describe('merchantLabel', () => {
  it('a label is "<name> · <code>"', () => {
    expect(merchantLabelText({ name: '데모상점', code: 'demoshop01' })).toBe('데모상점 · demoshop01')
  })

  it('null and undefined are both "no label" (NIT-3) — never "undefined" text or a crash', () => {
    expect(merchantLabelText(null)).toBeNull()
    expect(merchantLabelText(undefined)).toBeNull()
  })

  it('environment · MID and the payment method label', () => {
    expect(environmentMid('LIVE', 'mid_01JC0000000000000000000001')).toBe('LIVE · mid_01JC0000000000000000000001')
    expect(paymentMethodLabel('CARD')).toBe('카드')
  })
})
