import { describe, expect, it } from 'vitest'
import type { MerchantDetail, MerchantHistoryEntry } from '../api/adminApi'
import { HISTORY_TARGET_LABELS, historyTarget, historyTargetLabel } from './historyTarget'

/** ADMIN-HISTORY-KEYS-001 §3·§8 T-HK-1·T-HK-3 — 변경 이력 "대상" 열(D-ADMIN-UI-004 ③, D-MCODE-003). */
const MER = 'mer_01M71F76674MD68S1TZE5QZR0G'
const BIZ = 'biz_01M71F76674MD68S1TZE5QZR0H'
const detail = {
  merchant: { merchantId: MER, merchantCode: 'demoshop01', name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-10-01T00:00:00.000Z' },
  business: { businessId: BIZ, name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
} as unknown as MerchantDetail
const entry = (aggregateType: string, aggregateId: string): MerchantHistoryEntry => ({
  seq: 1, occurredAt: '2026-10-06T00:00:00.000Z', operation: 'Op', aggregateType, aggregateId, changeType: 'REGISTERED', actorRef: 'admin:adm_1',
})

describe('ADMIN-HISTORY-KEYS-001 historyTarget', () => {
  it('T-HK-1 merchant row → name · code (no mer_)', () => {
    expect(historyTarget(entry('Merchant', MER), detail)).toBe('데모 가맹점 · demoshop01')
  })

  it('T-HK-1 business row → name · masked registration number (no biz_)', () => {
    expect(historyTarget(entry('Business', BIZ), detail)).toBe('데모 사업자 · 123*******')
  })

  it.each([
    ['Merchant', 'mer_OTHER'],
    ['Business', 'biz_OTHER'],
    ['MerchantSite', `x${MER}`],
    ['Contract', `ref-${BIZ}`],
    ['Business', MER],
    ['Merchant', BIZ],
  ])('T-HK-1 fail-closed: %s %s that does not match the detail → "—"', (type, id) => {
    expect(historyTarget(entry(type, id), detail)).toBe('—')
  })

  it.each([
    ['MerchantSite', 'site_01M71F76674MD68S1TZE5QZR0J'],
    ['Contract', 'ctr_01M71F76DZR43JHRZH34068V9V'],
    ['MerchantAccount', 'mid_01M71F76XMMG2NYV5QYKJY7KEQ'],
    ['CardRouteActivation', 'cra_1'],
  ])('T-HK-1 other targets keep their identifier: %s', (type, id) => {
    expect(historyTarget(entry(type, id), detail)).toBe(id)
  })

  it('T-HK-3 Korean names for the 13 history aggregate types (no PolicyVersion); unknown → code', () => {
    expect(Object.keys(HISTORY_TARGET_LABELS).sort()).toEqual([
      'Business', 'CardRouteActivation', 'Contract', 'ContractServiceAgreement', 'CurrencyEnablement', 'Merchant', 'MerchantAccount',
      'MerchantSite', 'PaymentMethodActivation', 'PolicyAssignment', 'SiteActivationConfirmation', 'SiteAssignment', 'TransactionLimitPolicy',
    ])
    expect(historyTargetLabel('Business')).toBe('사업자')
    expect(historyTargetLabel('Merchant')).toBe('가맹점')
    expect(historyTargetLabel('CardRouteActivation')).toBe('카드사 Route 개통')
    expect(historyTargetLabel('PolicyVersion')).toBe('PolicyVersion')
    expect(historyTargetLabel('toString')).toBe('toString')
  })
})
