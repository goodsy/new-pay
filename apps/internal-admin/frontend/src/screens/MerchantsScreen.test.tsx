import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantHistoryEntry, MerchantListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/** Demo 식별자(MASTER 형식 — 접두사 + Crockford base32 26자). 업무 의미 없는 고정값. */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const MER2 = id('mer', 2)
const MER3 = id('mer', 3)
const BIZ1 = id('biz', 1)
const MASKED = '123*******'
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). 화면·요청 밖에 남으면 안 된다. */
const BRN = '1234567890'

const item = (merchantId: string, name: string): MerchantListItem => ({
  merchantId, merchantName: name, businessId: BIZ1, businessName: '데모 사업자', registrationNumberMasked: MASKED, lifecycle: 'ACTIVE',
  entitlementRevision: 4, midCount: 2, createdAt: '2026-10-01T00:00:00.000Z',
})

/** 상세 한 건(MERCHANT-OPS-001 §3.2 모양 전부 — Site 둘(심사 결과 있음/없음), 계약 1(SITES 범위), MID 1(배정·결제수단·통화·카드 Route), 한도 2). */
const detail: MerchantDetail = {
  merchant: { merchantId: MER1, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 4, createdAt: '2026-10-01T00:00:00.000Z' },
  business: { businessId: BIZ1, name: '데모 사업자', registrationNumberMasked: MASKED, lifecycle: 'ACTIVE' },
  sites: [
    { merchantSiteId: id('mst', 1), channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-10-02T00:00:00.000Z' } },
    { merchantSiteId: id('mst', 2), channelType: 'APP', channelIdentifier: 'test.example.app', lifecycle: 'ACTIVE', review: null },
  ],
  contracts: [{
    contractId: id('ctr', 1), createdAt: '2026-10-03T00:00:00.000Z',
    revisions: [{ revisionNo: 1, scopeKind: 'SITES', effectiveFrom: '2026-10-04T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-10-03T01:00:00.000Z', scopeSiteIds: [id('mst', 1)] }],
    agreements: [{ agreementId: id('csa', 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-10-03T02:00:00.000Z' }],
  }],
  accounts: [{
    merchantAccountId: id('mid', 1), lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
    issuedAt: '2026-10-05T00:00:00.000Z',
    assignments: [{ assignmentId: id('sas', 1), merchantSiteId: id('mst', 1), role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-10-05T00:00:00.000Z', effectiveTo: null }],
    paymentMethods: [{ activationId: id('pma', 1), paymentMethod: 'CARD', status: 'UNDER_REVIEW', registeredAt: '2026-10-05T01:00:00.000Z' }],
    currencies: [{ enablementId: id('cen', 1), currencyCode: 'KRW', effectiveFrom: '2026-10-05T00:00:00.000Z', effectiveTo: null, endedAt: null }],
    cardRoutes: [{ activationId: id('cra', 1), paymentMethod: 'CARD', acquirerCode: 'ACQ01', routeRef: 'ROUTE-A', status: 'SUSPENDED', registeredAt: '2026-10-06T00:00:00.000Z' }],
  }],
  limits: [
    {
      policyId: id('tlp', 1), scopeKind: 'CSA_DEFAULT', agreementId: id('csa', 1), merchantAccountId: null, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 1000000,
      dailyKind: 'UNLIMITED', dailyAmount: null, effectiveFrom: '2026-10-05T00:00:00.000Z', effectiveTo: null, endedAt: null,
    },
    {
      policyId: id('tlp', 2), scopeKind: 'MID_OVERRIDE', agreementId: null, merchantAccountId: id('mid', 1), currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 2 ** 53,
      dailyKind: 'LIMITED', dailyAmount: 5000000, effectiveFrom: '2026-10-05T00:00:00.000Z', effectiveTo: null, endedAt: null,
    },
  ],
  propagation: {
    latestRevision: 4, latestRevisionIssuedAt: '2026-10-06T00:00:00.000Z', latestAckRevision: 3, latestAckAt: '2026-10-05T23:00:00.000Z', effectConfirmedAt: null, effectBasis: null,
  },
}

const entry = (seq: number, changeType: string): MerchantHistoryEntry => ({
  seq, occurredAt: '2026-10-06T00:00:00.000Z', operation: 'RegisterCardRouteActivation', aggregateType: 'CardRouteActivation', aggregateId: id('cra', 1), changeType,
  actorRef: 'admin:adm_1',
})

const typeIn = async (label: string, value: string) => userEvent.type(screen.getByLabelText(label), value)
const search = async () => userEvent.click(screen.getByRole('button', { name: '검색' }))

/** 검색 → 첫 행 "상세"로 상세를 연다(목록 조회는 MER1 한 건). */
async function openDetail(overrides: Parameters<typeof fakeApi>[0]) {
  const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })), ...overrides })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
  await search()
  await userEvent.click(within(await screen.findByRole('row', { name: MER1 })).getByRole('button', { name: '상세' }))
  return api
}

describe('MerchantsScreen (MERCHANT-OPS-001 §7 AC 11 — read-only merchant lookup)', () => {
  it('search sends only the filled criteria (trimmed) and lists the masked registration number, MID count and entitlement revision', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await typeIn('가맹점 ID', `  ${MER1}  `)
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'TERMINATED')
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ merchantId: MER1, lifecycle: 'TERMINATED' })
    const row = await screen.findByRole('row', { name: MER1 })
    expect(row).toHaveTextContent('데모 가맹점')
    expect(row).toHaveTextContent(BIZ1)
    expect(row).toHaveTextContent(MASKED)
    expect(row).toHaveTextContent('활성')
    expect(within(row).getByText('2', { selector: '[data-col="midCount"]' })).toBeInTheDocument()
    expect(within(row).getByText('4', { selector: '[data-col="entitlementRevision"]' })).toBeInTheDocument()
    expect(row).toHaveTextContent('2026-10-01 09:00:00')
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
  })

  it('an empty result shows the empty row (same shape as found — no oracle wording)', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({})
    expect(await screen.findByText('조건에 맞는 가맹점이 없습니다.')).toBeInTheDocument()
  })

  it('"다음" sends the same criteria with after = nextCursor and replaces the page', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn()
        .mockResolvedValueOnce({ items: [item(MER1, '첫째'), item(MER2, '둘째')], nextCursor: MER2 })
        .mockResolvedValueOnce({ items: [item(MER3, '셋째')], nextCursor: null }),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await typeIn('사업자 ID', BIZ1)
    await search()
    await screen.findByRole('row', { name: MER2 })
    // 검색 뒤 입력란을 바꿔도 다음 쪽은 검색한 조건 그대로(조건이 섞이지 않는다).
    await userEvent.clear(screen.getByLabelText('사업자 ID'))
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { businessId: BIZ1, after: MER2 })
    expect(await screen.findByRole('row', { name: MER3 })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: MER1 })).toBeNull()
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
  })

  it('D-MOPS-003 §5.3: the registration number field is shown only when allowed (OPERATOR·APPROVER — display only, the server decides)', () => {
    const hidden = render(<MerchantsScreen api={fakeApi()} canSearchRegistrationNumber={false} />)
    expect(screen.queryByLabelText('사업자등록번호')).toBeNull()
    hidden.unmount()
    render(<MerchantsScreen api={fakeApi()} canSearchRegistrationNumber />)
    const input = screen.getByLabelText('사업자등록번호')
    expect(input).toHaveAttribute('type', 'text')
    expect(input).toHaveAttribute('inputmode', 'numeric')
    expect(input).toHaveAttribute('maxlength', '10')
    expect(input).toHaveAttribute('autocomplete', 'off')
    expect(screen.getByText(/사업자등록번호 검색은 매번 감사 기록/)).toBeInTheDocument()
  })

  it('a registration number that is not exactly 10 ASCII digits is not sent (client check — the server checks again)', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('사업자등록번호', '123456789')
    await search()
    expect(api.merchantSearch).not.toHaveBeenCalled()
    expect(screen.getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    expect(screen.getByLabelText('사업자등록번호')).toHaveAttribute('aria-invalid', 'true')
    await userEvent.clear(screen.getByLabelText('사업자등록번호'))
    await typeIn('사업자등록번호', '12345-6789')
    await search()
    expect(api.merchantSearch).not.toHaveBeenCalled()
  })

  it('a valid registration number is sent in the POST body, then cleared from the field; it is never rendered or stored', async () => {
    const log = vi.spyOn(console, 'log')
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: MER1 })) })
    const { container } = render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('사업자등록번호', BRN)
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ registrationNumber: BRN })
    expect(await screen.findByRole('row', { name: MER1 })).toHaveTextContent(MASKED)
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.innerHTML).not.toContain(BRN)
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain(BRN)
    expect(log).not.toHaveBeenCalled()
    // 번호를 화면 상태에 남기지 않으므로 등록번호 검색의 다음 쪽은 다시 보내지 않고 안내만 한다(사업자 ID로 다시 검색).
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
    expect(screen.getByText(/결과가 더 있습니다/)).toBeInTheDocument()
    log.mockRestore()
  })

  it('a refused registration number search (403 for the role) still clears the field and shows the fixed message', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('사업자등록번호', BRN)
    await search()
    expect(await screen.findByRole('alert')).toHaveTextContent('이 작업을 할 권한이 없습니다.')
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
  })

  it.each([
    [429, 'RATE_LIMITED', '조회가 너무 잦습니다'],
    [503, 'MASTER_UNAVAILABLE', '가맹점 정보 서비스에 연결할 수 없습니다'],
    [503, 'AUDIT_UNAVAILABLE', '감사 기록을 남길 수 없어'],
    [400, 'INVALID_INPUT', '입력 형식이 올바르지 않습니다.'],
  ] as const)('search error %i %s → fixed Korean message', async (status, code, message) => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => { throw apiError(status, code) }) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await search()
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
  })

  it('a session-ending answer goes back to login', async () => {
    const onSessionEnded = vi.fn()
    const api = fakeApi({ merchantSearch: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} onSessionEnded={onSessionEnded} />)
    await search()
    await vi.waitFor(() => expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })

  it('selecting a row opens the detail panels with the key values (basic info, sites + review, contract·CSA, MID·activation, limits, propagation)', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })),
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: MER1 })).getByRole('button', { name: '상세' }))
    expect(api.merchantDetail).toHaveBeenCalledWith(MER1)

    const basic = await screen.findByRole('region', { name: '기본정보' })
    expect(basic).toHaveTextContent('데모 가맹점')
    expect(basic).toHaveTextContent(BIZ1)
    expect(basic).toHaveTextContent(MASKED)

    const sites = screen.getByRole('region', { name: 'Site' })
    expect(within(sites).getByRole('row', { name: id('mst', 1) })).toHaveTextContent('shop.example.test')
    expect(within(sites).getByRole('row', { name: id('mst', 1) })).toHaveTextContent('승인')
    expect(within(sites).getByRole('row', { name: id('mst', 2) })).toHaveTextContent('심사 결정 없음')

    const contracts = screen.getByRole('region', { name: '계약·CSA' })
    expect(contracts).toHaveTextContent(id('ctr', 1))
    expect(contracts).toHaveTextContent('Site 지정')
    expect(contracts).toHaveTextContent(id('csa', 1))
    expect(contracts).toHaveTextContent('카드 PG 결제')

    const mids = screen.getByRole('region', { name: 'MID·개통' })
    expect(mids).toHaveTextContent(id('mid', 1))
    expect(mids).toHaveTextContent('기본 MID')
    expect(mids).toHaveTextContent('대표')
    expect(mids).toHaveTextContent('심사 중')
    expect(mids).toHaveTextContent('KRW')

    const limits = screen.getByRole('region', { name: '한도' })
    expect(within(limits).getByRole('row', { name: id('tlp', 1) })).toHaveTextContent('1,000,000원')
    expect(within(limits).getByRole('row', { name: id('tlp', 1) })).toHaveTextContent('무제한')
    // 안전 정수가 아닌 금액은 짐작해 보이지 않는다(L-2와 같은 이유).
    expect(within(limits).getByRole('row', { name: id('tlp', 2) })).toHaveTextContent('표시할 수 없는 금액')
    expect(limits).toHaveTextContent('Demo')

    const propagation = screen.getByRole('region', { name: '권리 전파 상태' })
    expect(propagation).toHaveTextContent('최신 revision 4')
    expect(propagation).toHaveTextContent('수신 확인 revision 3')
    expect(propagation).toHaveTextContent('효력 확인 전')
  })

  it('card routes show the acquirer code, route reference and status only (nothing else from the route, D-MS2-005)', async () => {
    const leaky = structuredClone(detail)
    // MASTER·ADMIN이 막아야 할 값이 잘못 섞여 와도 화면은 정해진 칸만 그린다(fail-closed 표시).
    Object.assign(leaky.accounts[0]!.cardRoutes[0]!, { institutionMerchantRef: 'INST-SECRET-777', detail: 'raw-detail' })
    await openDetail({ merchantDetail: vi.fn(async () => leaky), merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })) })
    const routes = await screen.findByRole('table', { name: '카드 Route' })
    expect(within(routes).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['인수사 코드', 'Route 참조', '상태'])
    const row = within(routes).getAllByRole('row')[1]!
    expect(within(row).getAllByRole('cell').map((c) => c.textContent)).toEqual(['ACQ01', 'ROUTE-A', '정지'])
    expect(document.body.innerHTML).not.toContain('INST-SECRET-777')
    expect(document.body.innerHTML).not.toContain('raw-detail')
    // Route 개통 ID·결제수단·등록 시각도 Route 표에는 그리지 않는다(정해진 세 칸만).
    expect(document.body.innerHTML).not.toContain('cra_')
    expect(within(routes).queryByText('2026-10-06 09:00:00')).toBeNull()
  })

  it('history: first page without after, then "이력 더 보기" with after = nextCursor (appends)', async () => {
    const api = await openDetail({
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn()
        .mockResolvedValueOnce({ items: [entry(10, 'REGISTERED'), entry(11, 'APPROVED')], nextCursor: 11 })
        .mockResolvedValueOnce({ items: [entry(12, 'SUSPENDED')], nextCursor: null }),
    })
    const history = await screen.findByRole('region', { name: '변경 이력' })
    expect(await within(history).findByRole('row', { name: '10' })).toHaveTextContent('RegisterCardRouteActivation')
    expect(api.merchantHistory).toHaveBeenNthCalledWith(1, MER1, undefined)
    await userEvent.click(within(history).getByRole('button', { name: '이력 더 보기' }))
    expect(api.merchantHistory).toHaveBeenNthCalledWith(2, MER1, 11)
    expect(await within(history).findByRole('row', { name: '12' })).toBeInTheDocument()
    expect(within(history).getByRole('row', { name: '10' })).toBeInTheDocument()
    expect(within(history).queryByRole('button', { name: '이력 더 보기' })).toBeNull()
  })

  it.each([
    [404, 'MERCHANT_NOT_FOUND', '가맹점을 찾을 수 없습니다.'],
    [403, 'FORBIDDEN', '이 작업을 할 권한이 없습니다.'],
    [503, 'AUDIT_UNAVAILABLE', '감사 기록을 남길 수 없어'],
  ] as const)('detail error %i %s → fixed message, no panels and no history call', async (status, code, message) => {
    const api = await openDetail({ merchantDetail: vi.fn(async () => { throw apiError(status, code) }) })
    expect(await screen.findByRole('alert')).toHaveTextContent(message)
    expect(screen.queryByRole('region', { name: '기본정보' })).toBeNull()
    expect(api.merchantHistory).not.toHaveBeenCalled()
  })
})
