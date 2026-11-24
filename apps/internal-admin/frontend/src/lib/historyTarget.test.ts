import { describe, expect, it } from 'vitest'
import type { MerchantDetail, MerchantHistoryEntry } from '../api/adminApi'
import { HISTORY_TARGET_LABELS, historyTarget, historyTargetLabel } from './historyTarget'

/** ADMIN-HISTORY-KEYS-001 §3·§8 T-HK-1·T-HK-3 — 변경 이력 "대상" 열(D-ADMIN-UI-004 ③, D-MCODE-003). */
const MER = 'mer_01M71F76674MD68S1TZE5QZR0G'
const BIZ = 'biz_01M71F76674MD68S1TZE5QZR0H'
const SITE = 'site_01M71F76674MD68S1TZE5QZR0J'
const CTR = 'ctr_01M71F76DZR43JHRZH34068V9V'
const MID = 'mid_01M71F76XMMG2NYV5QYKJY7KEQ'
const detail = {
  merchant: { merchantId: MER, merchantCode: 'demoshop01', name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-10-01T00:00:00.000Z' },
  business: { businessId: BIZ, name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
  // MERCHANT-IA-001 B3: 다른 대상의 사람용 값은 상세 응답에서 찾는다 — Site 하나·계약 하나·MID 하나(나머지는 비어 있음).
  sites: [{ merchantSiteId: SITE, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null }],
  contracts: [{ contractId: CTR, createdAt: '2026-10-01T00:00:00.000Z', revisions: [], agreements: [] }],
  accounts: [{ merchantAccountId: MID, assignments: [], paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [] }],
  limits: [],
} as unknown as MerchantDetail
const entry = (aggregateType: string, aggregateId: string): MerchantHistoryEntry => ({
  seq: 1, occurredAt: '2026-10-06T00:00:00.000Z', operation: 'Op', aggregateType, aggregateId, changeType: 'REGISTERED', actorRef: 'admin:adm_1',
})

describe('ADMIN-HISTORY-KEYS-001 historyTarget', () => {
  it('T-HK-1 merchant row → name · code (no mer_)', () => {
    expect(historyTarget(entry('Merchant', MER), detail)).toBe('데모 가맹점 · demoshop01')
  })

  it('T-HK-1 business row → name · masked registration number (no biz_)', () => {
    expect(historyTarget(entry('Business', BIZ), detail)).toBe('데모 사업자 · 123-45-67890')
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

  // 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 다른 대상은 식별자 그대로가 아니라 사람용 값(MID는 외부 식별자라 그대로),
  // 상세에서 찾지 못하면 빈 문자열(화면은 종류 이름만) — 내부 키를 돌려주지 않는다.
  it.each([
    ['MerchantSite', SITE, '웹 · shop.example.test'],
    ['Contract', CTR, '계약 1'],
    ['MerchantAccount', MID, MID],
    ['CardRouteActivation', 'cra_1', ''],
    ['MerchantSite', 'site_NOT_IN_DETAIL', ''],
    ['PolicyAssignment', 'pas_1', ''],
  ])('T-HK-1 other targets → human value, or "" when not in the detail (never the key): %s %s', (type, id, expected) => {
    expect(historyTarget(entry(type, id), detail)).toBe(expected)
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
