import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ServiceOpeningTimeline, serviceOpeningTimeline } from './ServiceOpeningTimeline'
import { MerchantsScreen } from '../screens/MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'

/**
 * ADMIN-UI-MERCHANT-001 — 가맹점 상세 '서비스 개통 현황'(참고 시안 #merchant 타임라인): 기존 상세 응답 값으로만 단계 상태를 계산한다(새 API·샘플 데이터 없음 — D-ADMIN-UI-003).
 * Named Fixture: 단계마다 업무 조건이 드러나게 만든다(Demo 식별자 — 업무 의미 없음).
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const SITE1 = id('site', 1)
type Detail = MerchantDetail
type Contract = Detail['contracts'][number]
type Account = Detail['accounts'][number]

/** 사업자·가맹점만 등록(계약 없음). */
function registeredOnly(patch: Partial<Detail> = {}): Detail {
  return {
    merchant: { merchantId: MER1, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [{ merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null }],
    contracts: [], accounts: [], limits: [], propagation: null, pendingApprovals: [],
    ...patch,
  }
}
const contract = (approved: boolean, agreements = 1): Contract => ({
  contractId: id('ctr', 1), createdAt: '2026-11-01T00:00:00.000Z',
  revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: approved ? '2026-11-10T16:00:00.000Z' : null, scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
  agreements: Array.from({ length: agreements }, (_, i) => ({ agreementId: id('csa', i + 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' })),
})
const mid = (patch: Partial<Account> = {}): Account => ({
  merchantAccountId: id('mid', 1), lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
  issuedAt: '2026-11-10T00:00:00.000Z', assignments: [], paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [], ...patch,
})
const pending = (kind: string) => ({ approvalId: 'apr_01JC0000000000000000000T01', kind, status: 'PENDING' })
const cardMethod = (status: string) => ({ activationId: id('act', 1), paymentMethod: 'CARD', status, registeredAt: '2026-11-11T01:00:00.000Z' })
const approvedSite = { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-11-11T02:00:00.000Z' } }

const NOW = new Date('2026-11-11T03:00:00.000Z')
const SITE2 = id('site', 2)
const MID2 = id('mid', 2)
/** MID의 대표(PRIMARY) Site 배정 — 오늘(NOW) 열려 있다. */
const primaryOn = (siteId: string) => [{ assignmentId: id('asg', 1), merchantSiteId: siteId, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }]
const pendingSite = (siteId: string) => ({ merchantSiteId: siteId, channelType: 'WEB', channelIdentifier: 'second.example.test', lifecycle: 'ACTIVE', review: null })
const states = (detail: Detail) => serviceOpeningTimeline(detail, NOW).map((s) => [s.label, s.state])

describe('serviceOpeningTimeline (ADMIN-UI-MERCHANT-001 — derived from the existing detail response only)', () => {
  it('registered business·merchant without a contract: registration in progress, the rest not started, fees·settlement planned', () => {
    expect(states(registeredOnly())).toEqual([
      ['사업자·계약 등록', 'IN_PROGRESS'],
      ['계약 승인', 'NOT_STARTED'],
      ['MID 발급', 'NOT_STARTED'],
      ['결제수단 개통·Site 심사', 'NOT_STARTED'],
      ['수수료·정산', 'LATER'],
    ])
  })

  it('a contract without a service agreement is still in progress; with one it is done', () => {
    expect(states(registeredOnly({ contracts: [contract(false, 0)] }))[0]).toEqual(['사업자·계약 등록', 'IN_PROGRESS'])
    expect(states(registeredOnly({ contracts: [contract(false)] }))[0]).toEqual(['사업자·계약 등록', 'DONE'])
  })

  it('contract approval: a pending 2-person approval is waiting, an approved revision is done', () => {
    expect(states(registeredOnly({ contracts: [contract(false)], pendingApprovals: [pending('MERCHANT_CONTRACT_APPROVAL')] }))[1]).toEqual(['계약 승인', 'WAITING'])
    expect(states(registeredOnly({ contracts: [contract(true)] }))[1]).toEqual(['계약 승인', 'DONE'])
  })

  it('MID issuance: a pending first-MID approval is waiting, an issued MID is done', () => {
    expect(states(registeredOnly({ contracts: [contract(true)], pendingApprovals: [pending('MERCHANT_FIRST_MID_ISSUANCE')] }))[2]).toEqual(['MID 발급', 'WAITING'])
    expect(states(registeredOnly({ contracts: [contract(true)], accounts: [mid()] }))[2]).toEqual(['MID 발급', 'DONE'])
  })

  it('payment method·Site review (coordinator decision — same rule as 개통 점검표 ②·③): done only when one MID has its open PRIMARY Site review APPROVED and the same MID CARD ACTIVE', () => {
    const base = { contracts: [contract(true)] }
    expect(states(registeredOnly({ ...base, accounts: [mid({ paymentMethods: [cardMethod('UNDER_REVIEW')] })] }))[3]).toEqual(['결제수단 개통·Site 심사', 'IN_PROGRESS'])
    expect(states(registeredOnly({ ...base, accounts: [mid()], pendingApprovals: [pending('MERCHANT_SITE_REVIEW_APPROVAL')] }))[3]).toEqual(['결제수단 개통·Site 심사', 'WAITING'])
    expect(states(registeredOnly({ ...base, accounts: [mid()], pendingApprovals: [pending('MERCHANT_PAYMENT_METHOD_ACTIVATION')] }))[3]).toEqual(['결제수단 개통·Site 심사', 'WAITING'])
    expect(states(registeredOnly({ ...base, sites: [approvedSite], accounts: [mid({ assignments: primaryOn(SITE1) })] }))[3]).toEqual(['결제수단 개통·Site 심사', 'IN_PROGRESS'])
    expect(states(registeredOnly({ ...base, sites: [approvedSite], accounts: [mid({ assignments: primaryOn(SITE1), paymentMethods: [cardMethod('ACTIVE')] })] }))[3]).toEqual(['결제수단 개통·Site 심사', 'DONE'])
  })

  it('payment method·Site review: an approved ADDITIONAL (non-PRIMARY) Site with CARD ACTIVE is only in progress', () => {
    const detail = registeredOnly({
      contracts: [contract(true)],
      sites: [pendingSite(SITE2), { ...approvedSite }],
      accounts: [mid({ assignments: primaryOn(SITE2), paymentMethods: [cardMethod('ACTIVE')] })],
    })
    expect(states(detail)[3]).toEqual(['결제수단 개통·Site 심사', 'IN_PROGRESS'])
  })

  it('payment method·Site review: CARD ACTIVE on MID A and the PRIMARY Site approved only on MID B is only in progress', () => {
    const detail = registeredOnly({
      contracts: [contract(true)],
      sites: [approvedSite, pendingSite(SITE2)],
      accounts: [
        mid({ assignments: primaryOn(SITE2), paymentMethods: [cardMethod('ACTIVE')] }),
        mid({ merchantAccountId: MID2, isDefault: false, assignments: primaryOn(SITE1) }),
      ],
    })
    expect(states(detail)[3]).toEqual(['결제수단 개통·Site 심사', 'IN_PROGRESS'])
  })

  it('fees·settlement is always planned (no server feature yet — B4); never done from sample data', () => {
    const everything = registeredOnly({ contracts: [contract(true)], sites: [approvedSite], accounts: [mid({ assignments: primaryOn(SITE1), paymentMethods: [cardMethod('ACTIVE')] })] })
    expect(states(everything)).toEqual([
      ['사업자·계약 등록', 'DONE'],
      ['계약 승인', 'DONE'],
      ['MID 발급', 'DONE'],
      ['결제수단 개통·Site 심사', 'DONE'],
      ['수수료·정산', 'LATER'],
    ])
  })

  it('renders an ordered list with the Korean state words and a reference-only note', () => {
    render(<ServiceOpeningTimeline detail={registeredOnly({ contracts: [contract(false)], pendingApprovals: [pending('MERCHANT_CONTRACT_APPROVAL')] })} now={NOW} />)
    const region = screen.getByRole('region', { name: '서비스 개통 현황' })
    expect(within(within(region).getByRole('list')).getAllByRole('listitem').map((li) => li.textContent)).toEqual([
      '사업자·계약 등록완료',
      '계약 승인승인 대기',
      'MID 발급미시작',
      '결제수단 개통·Site 심사미시작',
      '수수료·정산 (예정)B4 이후',
    ])
    expect(region).toHaveTextContent('참고용')
    expect(region).toHaveTextContent('MID 발급 뒤 나오는 개통 점검표')
  })
})

describe('MerchantsScreen detail — 서비스 개통 현황 sits above the existing panels (the 개통 점검표 stays)', () => {
  it('shows the timeline right after the detail heading, before 기본정보', async () => {
    const item: MerchantListItem = {
      merchantId: MER1, merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
      entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
    }
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
      merchantDetail: vi.fn(async () => registeredOnly({ contracts: [contract(true)] })),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} now={() => new Date('2026-11-11T03:00:00.000Z')} />)
    const user = userEvent.setup({ delay: null })
    await user.click(screen.getByRole('button', { name: '검색' }))
    await user.click(within(await screen.findByRole('row', { name: MER1 })).getByRole('button', { name: '상세' }))
    const timeline = await screen.findByRole('region', { name: '서비스 개통 현황' })
    const basics = screen.getByRole('region', { name: '기본정보' })
    expect(timeline.compareDocumentPosition(basics) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(within(timeline).getAllByRole('listitem')[1]).toHaveTextContent('계약 승인완료')
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })
})
