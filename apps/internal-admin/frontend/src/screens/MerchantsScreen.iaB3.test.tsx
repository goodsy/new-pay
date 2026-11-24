import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import { FirstMidRequest } from '../components/FirstMidRequest'
import type { MerchantDetail, MerchantHistoryEntry, MerchantListItem } from '../api/adminApi'
import { domOccurrences } from '../test/domScan'
import { fakeApi } from '../test/fakeApi'
import { openMerchantTab } from '../test/merchantDetailTabs'

/**
 * MERCHANT-IA-001 B3(명세 docs/repository/MERCHANT-IA-001-SCREEN-ITEMS.md §7 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"):
 * 가맹점 상세(모든 패널·개통 영역·이력·열 수 있는 폼·요청 결과 배너)의 DOM(글자·속성·입력값)에 내부 키 site_·ctr_·csa_·act_·apr_·sac_·cra_·tlp_·pas_·cur_·asg_가 없다
 * (기존 mer_·biz_ 단언은 MerchantsScreen.test 그대로). 내부 키는 상세·명령 호출 인자로만 간다. 사람용 값: Site = 채널 식별값(같은 채널이 여럿이면 상태·순서),
 * 계약 = "계약 n", 서비스 약정 = "서비스: 카드 PG 결제", 한도 범위 = "MID …"/"서비스: …", 이력 대상 = 종류 + 사람용 값(없으면 종류만). MID는 외부 식별자라 그대로.
 * Demo 식별자(MASTER 실제 접두사), 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MID1 = id('mid', 1)
const SITE1 = id('site', 1)
const SITE_OLD = id('site', 2)
const SITE3 = id('site', 3)
const CTR1 = id('ctr', 1)
const CSA1 = id('csa', 1)
const ACT1 = id('act', 1)
const SAC1 = id('sac', 1)
const CUR1 = id('cur', 1)
const CRA1 = id('cra', 1)
const TLP1 = id('tlp', 1)
const TLP2 = id('tlp', 2)
const PAS1 = id('pas', 1)
const ASG1 = id('asg', 1)
const APR = 'apr_01JC0000000000000000000B33'
const NOW = () => new Date('2026-11-11T03:00:00.000Z')
const PREFIXES = ['site_', 'ctr_', 'csa_', 'act_', 'apr_', 'sac_', 'cra_', 'tlp_', 'pas_', 'cur_', 'asg_', 'mer_', 'biz_']

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 1, createdAt: '2026-11-01T00:00:00.000Z',
}
/**
 * Named Fixture: 모든 내부 키 종류가 한 번씩 나오는 가맹점 — Site 셋(같은 채널 shop.example.test가 해지·활성 두 개 + 심사 대기 하나), 계약 1(승인 Revision 1 + 미승인 Revision 2),
 * 서비스 약정 1, MID 1(PRIMARY 배정·카드 개통 심사 중·적용 확인·KRW·카드 Route), 한도 2(서비스 약정 기본·MID 개별), 승인 대기 1.
 */
function detail(patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE_OLD, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'TERMINATED', review: { decision: 'APPROVED', decidedAt: '2026-11-02T00:00:00.000Z' }, registeredBy: 'admin:adm_SR' },
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-11-10T00:00:00.000Z' }, registeredBy: 'admin:adm_SR' },
      { merchantSiteId: SITE3, channelType: 'APP', channelIdentifier: 'com.example.app', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
    ],
    contracts: [{
      contractId: CTR1, createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [
        { revisionNo: 1, scopeKind: 'SITES', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-10T16:00:00.000Z', scopeSiteIds: [SITE1], registeredBy: 'admin:adm_X' },
        { revisionNo: 2, scopeKind: 'SITES', effectiveFrom: '2026-11-20T15:00:00.000Z', effectiveTo: null, approvedAt: null, scopeSiteIds: [SITE1, SITE3], registeredBy: 'admin:adm_X' },
      ],
      agreements: [{ agreementId: CSA1, serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [{
      merchantAccountId: MID1, lifecycle: 'ACTIVE', isDefault: true, issuingContractId: CTR1, issuingAgreementId: CSA1, issuingRevisionNo: 1, issuedAt: '2026-11-10T17:00:00.000Z',
      assignments: [{ assignmentId: ASG1, merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T17:00:00.000Z', effectiveTo: null }],
      paymentMethods: [{ activationId: ACT1, paymentMethod: 'CARD', status: 'UNDER_REVIEW', registeredAt: '2026-11-11T01:00:00.000Z', registeredBy: 'admin:adm_SR' }],
      currencies: [{ enablementId: CUR1, currencyCode: 'KRW', effectiveFrom: '2026-11-10T18:00:00.000Z', effectiveTo: null, endedAt: null }],
      cardRoutes: [{ activationId: CRA1, paymentMethod: 'CARD', acquirerCode: 'ACQ01', routeRef: 'ROUTE-A', status: 'ACTIVE', registeredAt: '2026-11-10T19:00:00.000Z' }],
      siteConfirmations: [{ confirmationId: SAC1, merchantSiteId: SITE1, paymentMethod: 'CARD', activationId: ACT1, confirmedAt: '2026-11-11T02:00:00.000Z', revoked: false }],
    }],
    limits: [
      { policyId: TLP1, scopeKind: 'CSA_DEFAULT', agreementId: CSA1, merchantAccountId: null, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 1000000, dailyKind: 'UNLIMITED', dailyAmount: null, effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null, endedAt: null },
      { policyId: TLP2, scopeKind: 'MID_OVERRIDE', agreementId: null, merchantAccountId: MID1, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 500000, dailyKind: 'LIMITED', dailyAmount: 5000000, effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null, endedAt: null },
    ],
    propagation: null,
    pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_SITE_REVIEW_APPROVAL', status: 'PENDING', revisionNo: null }],
    ...patch,
  }
}
const h = (seq: number, aggregateType: string, aggregateId: string): MerchantHistoryEntry => ({
  seq, occurredAt: '2026-11-10T00:00:00.000Z', operation: 'Op', aggregateType, aggregateId, changeType: 'REGISTERED', actorRef: 'admin:adm_1',
})
const HISTORY: MerchantHistoryEntry[] = [
  h(1, 'MerchantSite', SITE3), h(2, 'Contract', CTR1), h(3, 'ContractServiceAgreement', CSA1), h(4, 'MerchantAccount', MID1), h(5, 'SiteAssignment', ASG1),
  h(6, 'PaymentMethodActivation', ACT1), h(7, 'SiteActivationConfirmation', SAC1), h(8, 'CurrencyEnablement', CUR1), h(9, 'CardRouteActivation', CRA1),
  h(10, 'TransactionLimitPolicy', TLP2), h(11, 'PolicyAssignment', PAS1), h(12, 'MerchantSite', id('site', 99)),
]

function noInternalKeys() {
  for (const prefix of PREFIXES) expect(domOccurrences(document.body, prefix), prefix).toEqual([])
}

async function openDetail(d: MerchantDetail, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => d),
    merchantHistory: vi.fn(async () => ({ items: HISTORY, nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval canRegisterRevision now={NOW} newSubmissionId={() => 'f'.repeat(32)} onOpenApprovals={vi.fn()} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  await openMerchantTab('변경 이력') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
  await within(await screen.findByRole('region', { name: '변경 이력' })).findByRole('row', { name: '12' })
  return api
}

describe('MERCHANT-IA-001 B3 — 내부 식별자 → 사람용 값', () => {
  it('상세 전체(폼을 연 상태 포함)에 site_·ctr_·csa_·act_·apr_·sac_·cra_·tlp_·pas_·cur_·asg_·mer_·biz_가 없다', async () => {
    await openDetail(detail())
    noInternalKeys()
    // 열 수 있는 폼을 모두 연다: 새 Revision 등록(선택 Site), 계약 승인 요청, 개통 승인 요청, 심사 승인 요청, 심사 거절, 통화 활성화 요청은 KRW가 있어 없음.
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    await userEvent.click(screen.getByRole('radio', { name: '선택 Site' }))
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '승인 요청' }))
    await openMerchantTab('MID·개통') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '개통 승인 요청' }))
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const review = screen.getByRole('table', { name: 'Site 심사' })
    await userEvent.click(within(review).getByRole('button', { name: '심사 거절' }))
    noInternalKeys()
  })

  it('Site = 채널 식별값(같은 채널이 둘이면 상태·순서로 구별), 계약 = "계약 1", 서비스 약정 = "서비스: 카드 PG 결제", MID 발급 근거·Site 배정·적용 확인도 사람용 값', async () => {
    await openDetail(detail())
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const sites = screen.getByRole('table', { name: 'Site 목록' })
    expect(within(sites).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['채널', '상태', '심사 결과'])
    expect(within(sites).getByRole('row', { name: '웹 · shop.example.test (해지 · 1번째)' })).toBeInTheDocument()
    expect(within(sites).getByRole('row', { name: '웹 · shop.example.test (활성 · 2번째)' })).toBeInTheDocument()
    expect(within(sites).getByRole('row', { name: '앱 · com.example.app' })).toBeInTheDocument()
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const contracts = screen.getByRole('region', { name: '계약·CSA' })
    expect(contracts).toHaveTextContent('계약 1')
    expect(within(contracts).getByRole('table', { name: '계약 1 revision' })).toBeInTheDocument()
    expect(within(within(contracts).getByRole('row', { name: 'revision 2' })).getByText('앱 · com.example.app')).toBeInTheDocument()
    expect(within(contracts).getByRole('list', { name: '계약 1 서비스' })).toHaveTextContent('서비스: 카드 PG 결제')
    await openMerchantTab('MID·개통') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const mids = screen.getByRole('region', { name: 'MID·개통' })
    expect(mids).toHaveTextContent(MID1)
    expect(mids).toHaveTextContent('근거 계약 1 Revision 1 · 서비스: 카드 PG 결제')
    expect(within(within(mids).getByRole('table', { name: 'Site 배정' })).getAllByRole('row')[1]).toHaveTextContent('웹 · shop.example.test (활성 · 2번째)')
    const confirm = within(mids).getByRole('table', { name: 'Site 적용 확인' })
    expect(within(confirm).getAllByRole('columnheader').map((c) => c.textContent)).toEqual(['Site', '결제수단', '확인', '상태'])
    expect(within(confirm).getAllByRole('row')[1]).toHaveTextContent('웹 · shop.example.test (활성 · 2번째)')
  })

  it('한도 범위는 "서비스: 카드 PG 결제" / "MID <mid>"; 승인 대기 배너는 승인 ID 대신 "승인함에서 보기"', async () => {
    await openDetail(detail())
    await openMerchantTab('한도') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const limits = screen.getByRole('table', { name: '한도 목록' })
    const rows = within(limits).getAllByRole('row').slice(1)
    expect(rows[0]).toHaveTextContent('서비스: 카드 PG 결제')
    expect(rows[1]).toHaveTextContent(`MID ${MID1}`)
    const pending = screen.getByRole('status', { name: '승인 대기' })
    expect(pending).toHaveTextContent('Site 심사 승인')
    expect(within(pending).getByRole('button', { name: '승인함에서 보기' })).toBeInTheDocument()
  })

  it('승인 요청 결과 배너: 승인 ID 없이 무엇을 요청했는지 + "승인함에서 보기"; 개통 승인 요청 폼 이름은 결제수단', async () => {
    const requestMerchantPaymentMethodActivation = vi.fn(async () => ({ approvalId: APR }))
    await openDetail(detail({ pendingApprovals: [] }), { requestMerchantPaymentMethodActivation })
    await openMerchantTab('MID·개통') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '개통 승인 요청' }))
    const form = screen.getByRole('form', { name: '개통 승인 요청 카드' })
    await userEvent.type(within(form).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0001')
    await userEvent.click(within(form).getByRole('button', { name: '승인 요청 보내기' }))
    // 내부 키는 명령 호출 인자로만 간다.
    expect(requestMerchantPaymentMethodActivation).toHaveBeenCalledWith({ merchantId: MER1, merchantAccountId: MID1, activationId: ACT1, evidenceRef: 'VAN-REPLY/2026-0001' })
    const result = await screen.findByRole('status', { name: '승인 요청 결과' })
    expect(result).toHaveTextContent('결제수단 개통 승인')
    expect(within(result).getByRole('button', { name: '승인함에서 보기' })).toBeInTheDocument()
    noInternalKeys()
  })

  it('변경 이력 대상 = 한국어 종류 이름 + 사람용 값(없으면 종류만), 영문 종류 코드·내부 키 없음', async () => {
    await openDetail(detail())
    await openMerchantTab('변경 이력') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const table = screen.getByRole('table', { name: '변경 이력 목록' })
    const target = (seq: number) => within(within(table).getByRole('row', { name: String(seq) })).getAllByRole('cell')[2]!
    expect(target(1)).toHaveTextContent(/^사이트앱 · com\.example\.app$/)
    expect(target(2)).toHaveTextContent(/^계약계약 1$/)
    expect(target(3)).toHaveTextContent(/^서비스 약정\(CSA\)서비스: 카드 PG 결제$/)
    expect(target(4)).toHaveTextContent(new RegExp(`^MID${MID1}$`))
    expect(target(5)).toHaveTextContent(`${MID1} · 웹 · shop.example.test (활성 · 2번째)`)
    expect(target(6)).toHaveTextContent(`${MID1} · 카드`)
    expect(target(7)).toHaveTextContent('웹 · shop.example.test (활성 · 2번째) · 카드')
    expect(target(8)).toHaveTextContent(`${MID1} · KRW`)
    expect(target(9)).toHaveTextContent(`${MID1} · 인수사 ACQ01`)
    expect(target(10)).toHaveTextContent(`MID ${MID1} · KRW`)
    expect(target(11)).toHaveTextContent(/^정책 배정$/)
    // 상세에 없는 Site(예: 다른 경로로 지워진 값) — 종류만, 키는 보이지 않는다.
    expect(target(12)).toHaveTextContent(/^사이트$/)
    for (const code of ['MerchantSite', 'CardRouteActivation', 'PolicyAssignment']) expect(table).not.toHaveTextContent(code)
  })

  it('새 Revision 등록 폼: 제목은 "계약 1", 선택 Site 후보는 채널 식별값 — 보내는 본문에는 내부 키', async () => {
    const registerContractRevision = vi.fn(async () => ({ outcome: 'SUCCEEDED' as const, reference: `${CTR1}#3`, replayed: false }))
    await openDetail(detail(), { registerContractRevision })
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    const form = screen.getByRole('region', { name: '새 Revision 등록 — 계약 1' })
    await userEvent.click(within(form).getByRole('radio', { name: '선택 Site' }))
    await userEvent.click(within(form).getByRole('checkbox', { name: '앱 · com.example.app' }))
    await userEvent.type(within(form).getByLabelText('시작일'), '2026-11-30')
    await userEvent.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    expect(registerContractRevision).toHaveBeenCalledWith(expect.objectContaining({ contractId: CTR1, scopeSiteIds: [SITE3] }))
    expect(await within(form).findByText(/Revision 3/)).toBeInTheDocument()
    noInternalKeys()
  })
})

describe('MERCHANT-IA-001 B3 — 첫 MID 발급 요청 폼', () => {
  it('Revision·서비스 약정·PRIMARY Site 선택지의 글자와 value에 내부 키가 없고, 보내는 본문에는 내부 키', async () => {
    const requestMerchantFirstMid = vi.fn(async () => ({ approvalId: APR }))
    await openDetail(detail({ accounts: [], limits: [], pendingApprovals: [] }), { requestMerchantFirstMid })
    await userEvent.click(screen.getByRole('button', { name: '첫 MID 발급 요청' }))
    const form = screen.getByRole('form', { name: '첫 MID 발급 요청' })
    expect(within(within(form).getByLabelText('Revision')).getAllByRole('option').map((o) => o.textContent)).toEqual([expect.stringMatching(/^계약 1 Revision 1 · 2026-11-11 시작$/)])
    expect(within(within(form).getByLabelText('서비스 계약(CSA)')).getAllByRole('option').map((o) => o.textContent)).toEqual(['서비스: 카드 PG 결제'])
    expect(within(within(form).getByLabelText('PRIMARY Site')).getAllByRole('option').map((o) => o.textContent)).toEqual(['웹 · shop.example.test (활성 · 2번째)'])
    noInternalKeys()
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'CONTRACT-2026-0001')
    await userEvent.click(within(form).getByRole('button', { name: '발급 요청 보내기' }))
    expect(requestMerchantFirstMid).toHaveBeenCalledWith({ merchantId: MER1, contractId: CTR1, agreementId: CSA1, revisionNo: 1, primarySiteId: SITE1, evidenceRef: 'CONTRACT-2026-0001' })
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('첫 MID 발급')
    noInternalKeys()
  })
})

describe('MERCHANT-IA-001 B3 리뷰 반영', () => {
  /** Named Fixture: 같은 식별값 shop.example.test가 웹·앱 Site 둘(둘 다 활성·심사 대기) — 종류로 구별되어야 하고 "n번째"가 붙지 않는다. */
  const sameIdentifier = (patch: Partial<MerchantDetail> = {}) => detail({
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
      { merchantSiteId: SITE3, channelType: 'APP', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
    ],
    contracts: [{
      contractId: CTR1, createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-10T16:00:00.000Z', scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
      agreements: [{ agreementId: CSA1, serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    pendingApprovals: [],
    ...patch,
  })

  it('MINOR-1: 같은 식별값의 웹·앱 Site는 채널 종류로 구별된다 — Site 목록·Site 심사·Revision 폼 후보(순번 꼬리 없음)', async () => {
    await openDetail(sameIdentifier())
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    expect(within(screen.getByRole('table', { name: 'Site 목록' })).getAllByRole('row').slice(1).map((r) => r.getAttribute('aria-label'))).toEqual(['웹 · shop.example.test', '앱 · shop.example.test'])
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const review = screen.getByRole('table', { name: 'Site 심사' })
    expect(within(review).getByRole('row', { name: '심사 웹 · shop.example.test' })).toBeInTheDocument()
    expect(within(review).getByRole('row', { name: '심사 앱 · shop.example.test' })).toBeInTheDocument()
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    const form = screen.getByRole('region', { name: '새 Revision 등록 — 계약 1' })
    await userEvent.click(within(form).getByRole('radio', { name: '선택 Site' }))
    expect(within(form).getAllByRole('checkbox').map((c) => c.closest('label')?.textContent)).toEqual(['웹 · shop.example.test', '앱 · shop.example.test'])
    expect(document.body).not.toHaveTextContent('번째')
    noInternalKeys()
  })

  it('MINOR-1: 첫 MID 발급 폼의 PRIMARY Site 선택지도 종류로 구별된다', async () => {
    await openDetail(sameIdentifier({ accounts: [], limits: [] }))
    await userEvent.click(screen.getByRole('button', { name: '첫 MID 발급 요청' }))
    const form = screen.getByRole('form', { name: '첫 MID 발급 요청' })
    expect(within(within(form).getByLabelText('PRIMARY Site')).getAllByRole('option').map((o) => o.textContent)).toEqual(['웹 · shop.example.test', '앱 · shop.example.test'])
    noInternalKeys()
  })

  it('NIT-1: 승인 대기가 없으면 "심사 승인 요청" 폼까지 열어도 DOM에 내부 키가 없다(폼 이름도 사람용 값)', async () => {
    await openDetail(detail({ pendingApprovals: [] }))
    await openMerchantTab('Site·계약') // MERCHANT-IA-001 B4: 탭 열기 단계만 더함
    const review = screen.getByRole('table', { name: 'Site 심사' })
    await userEvent.click(within(within(review).getByRole('row', { name: '심사 앱 · com.example.app' })).getByRole('button', { name: '심사 승인 요청' }))
    expect(screen.getByRole('form', { name: '심사 승인 요청 앱 · com.example.app' })).toBeInTheDocument()
    noInternalKeys()
  })

  it('NIT-3: 상세를 다시 읽어 고른 PRIMARY Site가 후보에서 빠지면 첫 후보로 다시 맞추고, 보내는 값도 그 Site다', async () => {
    const requestMerchantFirstMid = vi.fn(async () => ({ approvalId: APR }))
    const api = fakeApi({ requestMerchantFirstMid })
    const before = sameIdentifier({ accounts: [], limits: [] })
    const { rerender } = render(<FirstMidRequest api={api} detail={before} now={NOW()} onRequested={vi.fn()} />)
    const form = screen.getByRole('form', { name: '첫 MID 발급 요청' })
    await userEvent.selectOptions(within(form).getByLabelText('PRIMARY Site'), '앱 · shop.example.test')
    // 다시 읽은 상세에서는 앱 Site가 해지되어 후보에서 빠졌다.
    const after = sameIdentifier({ accounts: [], limits: [], sites: [before.sites[0]!, { ...before.sites[1]!, lifecycle: 'TERMINATED' }] })
    rerender(<FirstMidRequest api={api} detail={after} now={NOW()} onRequested={vi.fn()} />)
    const select = within(form).getByLabelText('PRIMARY Site') as HTMLSelectElement
    expect(select.selectedOptions[0]?.textContent).toBe('웹 · shop.example.test')
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'CONTRACT-2026-0002')
    await userEvent.click(within(form).getByRole('button', { name: '발급 요청 보내기' }))
    expect(requestMerchantFirstMid).toHaveBeenCalledWith(expect.objectContaining({ primarySiteId: SITE1 }))
  })
})
