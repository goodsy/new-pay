import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi, storageIsEmpty } from '../test/fakeApi'
import { formatKst } from '../lib/format'

/**
 * MERCHANT-OPS-002 §8.4 B3-2-6 — 조회 상세 "개통" 영역의 통화(§9.17·§9.26, D-MOPS-009 ②): 통화 표(MASTER 확정 효력 시작 시각·상태), OPERATOR "통화 활성화 요청"(2인 — KRW만 C-41,
 * 라디오 "승인 실행 즉시" / "날짜 지정(내일 이후)", 날짜 선택기 최솟값 = 내일 KST, DATE인데 날짜가 없으면 보내기 불가, 종료일 "이 날 0시 종료(미포함)", 근거 = 기관(카드사/VAN) 회신 참조),
 * 같은 그룹(CURRENCY) 대기 중·열린 KRW 기록·MID 비활성이면 버튼 없음, 점검표 ⑤(효력 전 KRW는 미충족 + "효력 전"). Demo 식별자, 기준 시각 2026-11-11T03:00Z(= 12:00 KST → 내일 2026-11-12).
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MID1 = id('mid', 1)
const CUR1 = id('cur', 1)
const SITE1 = id('site', 1)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')
const APR = 'apr_01JC0000000000000000000B32'

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 1, createdAt: '2026-11-01T00:00:00.000Z',
}
type Account = MerchantDetail['accounts'][number]
type CurrencyRow = Account['currencies'][number]
const krw = (patch: Partial<CurrencyRow> = {}): CurrencyRow => ({ enablementId: CUR1, currencyCode: 'KRW', effectiveFrom: '2026-11-11T02:30:00.123Z', effectiveTo: null, endedAt: null, ...patch })
const account = (patch: Partial<Account> = {}): Account => ({
  merchantAccountId: MID1, lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
  issuedAt: '2026-11-10T00:00:00.000Z',
  assignments: [{ assignmentId: id('asg', 1), merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
  paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [], ...patch,
})
function detail(patch: Partial<MerchantDetail> = {}, accountPatch: Partial<Account> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [{ merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' }],
    contracts: [{
      contractId: id('ctr', 1), createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-10T16:00:00.000Z', scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
      agreements: [{ agreementId: id('csa', 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [account(accountPatch)], limits: [],
    propagation: { latestRevision: 3, latestRevisionIssuedAt: '2026-11-11T00:00:00.000Z', latestAckRevision: 3, latestAckAt: '2026-11-11T00:00:01.000Z', effectConfirmedAt: '2026-11-11T00:00:01.000Z', effectBasis: 'ACK' },
    pendingApprovals: [],
    ...patch,
  }
}

async function openDetail(canRequestApproval: boolean, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail()),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} now={NOW} newSubmissionId={() => 'c'.repeat(32)} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  return { api, area: await screen.findByRole('region', { name: `개통 ${MID1}` }) }
}

describe('MerchantsScreen — B3-2 currency in the "개통" area (MERCHANT-OPS-002 §8.4 B3-2-6)', () => {
  it('a VIEWER sees the currency row with the MASTER-confirmed start time and its state, but no request button', async () => {
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => detail({}, { currencies: [krw()] })) })
    const row = within(within(area).getByRole('table', { name: '통화' })).getByRole('row', { name: CUR1 })
    expect(row).toHaveTextContent('KRW')
    expect(row).toHaveTextContent(formatKst('2026-11-11T02:30:00.123Z'))
    expect(row).toHaveTextContent('효력 중')
    expect(within(area).queryByRole('button', { name: '통화 활성화 요청' })).toBeNull()
  })

  it('AT_EXECUTION (default): the evidence is required; it sends {merchantId, MID, KRW, AT_EXECUTION, evidenceRef} without dates and shows the result', async () => {
    const requestMerchantCurrencyEnablement = vi.fn(async () => ({ approvalId: APR }))
    const { area } = await openDetail(true, { requestMerchantCurrencyEnablement })
    await userEvent.click(within(area).getByRole('button', { name: '통화 활성화 요청' }))
    const form = within(area).getByRole('form', { name: `통화 활성화 요청 ${MID1}` })
    expect(within(form).getByRole('radio', { name: '승인 실행 즉시' })).toBeChecked()
    const send = within(form).getByRole('button', { name: '승인 요청 보내기' })
    expect(send).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0001')
    await userEvent.click(send)
    expect(requestMerchantCurrencyEnablement).toHaveBeenCalledWith({
      merchantId: MER1, merchantAccountId: MID1, currency: 'KRW', effectiveFromMode: 'AT_EXECUTION', evidenceRef: 'VAN-REPLY/2026-0001',
    })
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('통화 활성화')
    expect(storageIsEmpty()).toBe(true)
  })

  it('DATE: the date picker minimum is tomorrow KST; without a date it cannot be sent; with a start and an end date it sends both (end = "이 날 0시 종료(미포함)")', async () => {
    const requestMerchantCurrencyEnablement = vi.fn(async () => ({ approvalId: APR }))
    const { area } = await openDetail(true, { requestMerchantCurrencyEnablement })
    await userEvent.click(within(area).getByRole('button', { name: '통화 활성화 요청' }))
    const form = within(area).getByRole('form', { name: `통화 활성화 요청 ${MID1}` })
    await userEvent.click(within(form).getByRole('radio', { name: '날짜 지정(내일 이후)' }))
    await userEvent.type(within(form).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0002')
    const start = within(form).getByLabelText('효력 시작일(KST)')
    expect(start).toHaveAttribute('min', '2026-11-12')
    const send = within(form).getByRole('button', { name: '승인 요청 보내기' })
    expect(send).toBeDisabled()
    // 오늘(최솟값 밖)은 보낼 수 없다.
    await userEvent.type(start, '2026-11-11')
    expect(send).toBeDisabled()
    await userEvent.clear(start)
    await userEvent.type(start, '2026-11-12')
    const end = within(form).getByLabelText(/종료일\(선택\)/)
    expect(form).toHaveTextContent('이 날 0시 종료(미포함)')
    await userEvent.type(end, '2026-12-01')
    expect(send).toBeEnabled()
    await userEvent.click(send)
    expect(requestMerchantCurrencyEnablement).toHaveBeenCalledWith({
      merchantId: MER1, merchantAccountId: MID1, currency: 'KRW', effectiveFromMode: 'DATE', effectiveFromDate: '2026-11-12', effectiveToDate: '2026-12-01',
      evidenceRef: 'VAN-REPLY/2026-0002',
    })
  })

  it('the request button is hidden while a CURRENCY-group approval is pending, when an unended KRW record exists, or when the MID is not active', async () => {
    const cases: MerchantDetail[] = [
      detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_CURRENCY_ENABLEMENT', status: 'PENDING', revisionNo: null }] }),
      detail({}, { currencies: [krw()] }),
      detail({}, { currencies: [krw({ effectiveFrom: '2026-11-12T15:00:00.000Z' })] }),
      detail({}, { lifecycle: 'SUSPENDED' }),
    ]
    for (const d of cases) {
      const { area } = await openDetail(true, { merchantDetail: vi.fn(async () => d) })
      expect(within(area).queryByRole('button', { name: '통화 활성화 요청' })).toBeNull()
      document.body.innerHTML = ''
    }
    // 대조군: 지난 기간(종료일이 지금보다 앞)만 있는 KRW는 다시 요청할 수 있다.
    const { area } = await openDetail(true, { merchantDetail: vi.fn(async () => detail({}, { currencies: [krw({ effectiveTo: '2026-11-10T15:00:00.000Z' })] })) })
    expect(within(area).getByRole('button', { name: '통화 활성화 요청' })).toBeInTheDocument()
  })

  it('checklist ⑤: an open KRW is met; a KRW starting tomorrow is not met with "효력 전" and its row says 효력 전', async () => {
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => detail({}, { currencies: [krw()] })) })
    const list = within(area).getByRole('region', { name: '개통 점검표' })
    expect(within(list).getByRole('listitem', { name: /^⑤ / }).getAttribute('data-state')).toBe('MET')
    document.body.innerHTML = ''
    const future = krw({ effectiveFrom: '2026-11-11T15:00:00.000Z' })
    const { area: later } = await openDetail(false, { merchantDetail: vi.fn(async () => detail({}, { currencies: [future] })) })
    const item5 = within(within(later).getByRole('region', { name: '개통 점검표' })).getByRole('listitem', { name: /^⑤ / })
    expect(item5.getAttribute('data-state')).toBe('NOT_MET')
    expect(item5).toHaveTextContent('효력 전')
    expect(within(within(later).getByRole('table', { name: '통화' })).getByRole('row', { name: CUR1 })).toHaveTextContent('효력 전')
  })

  // 기대값 변경(MERCHANT-IA-001 B1 — 명세 rev 0.2 §3.6·§7 B1): "승인 시작 조건: Route·수수료·정산·한도 필요(B3-3·B4)" 줄은 항목 ⑥⑦과 같은 말이라 뺐고(⑥⑦은 그대로 남는다),
  // L-17 Demo 한계 줄은 화면이 환경을 구별할 수 없어 일반 문구("가맹점의 결제 호출에는 API 키가 따로 필요하며, 이 화면에서 발급하지 않습니다.")로 바꿨다. 작업 배치 이름은 화면에 없다.
  it('the checklist explains what remains: items ⑥⑦ stay (no duplicate "승인 시작 조건" line, no batch names); the API key line is generic', async () => {
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => detail({}, { currencies: [krw()] })) })
    const list = within(area).getByRole('region', { name: '개통 점검표' })
    // 이 Fixture는 결제수단·Site 심사가 아직이라 결제 생성 조건 요약은 미충족(참고).
    expect(list).toHaveTextContent('결제 생성 조건: 미충족(참고)')
    expect(within(list).getByRole('listitem', { name: /^⑥ / })).toBeInTheDocument()
    expect(within(list).getByRole('listitem', { name: /^⑦ / })).toBeInTheDocument()
    expect(list).not.toHaveTextContent('승인 시작 조건:')
    for (const word of ['B3-3', 'B4', 'L-17']) expect(list).not.toHaveTextContent(word)
    expect(list).toHaveTextContent('가맹점의 결제 호출에는 API 키가 따로 필요하며, 이 화면에서 발급하지 않습니다.')
  })
})
