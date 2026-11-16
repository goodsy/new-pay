import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantHistoryEntry, MerchantListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/** Demo 식별자(MASTER 형식 — 접두사 + Crockford base32 26자). 업무 의미 없는 고정값. */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MER2 = id('mer', 2)
const MER3 = id('mer', 3)
const BIZ1 = id('biz', 1)
const MASKED = '123*******'
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). 화면·요청 밖에 남으면 안 된다. */
const BRN = '1234567890'

const item = (merchantId: string, name: string): MerchantListItem => ({
  merchantId, merchantCode: codeOf(merchantId), merchantName: name, businessId: BIZ1, businessName: '데모 사업자', registrationNumberMasked: MASKED, lifecycle: 'ACTIVE',
  entitlementRevision: 4, midCount: 2, createdAt: '2026-10-01T00:00:00.000Z',
})

/** 상세 한 건(MERCHANT-OPS-001 §3.2 모양 전부 — Site 둘(심사 결과 있음/없음), 계약 1(SITES 범위), MID 1(배정·결제수단·통화·카드 Route), 한도 2). */
const detail: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 4, createdAt: '2026-10-01T00:00:00.000Z' },
  business: { businessId: BIZ1, name: '데모 사업자', registrationNumberMasked: MASKED, lifecycle: 'ACTIVE' },
  sites: [
    { merchantSiteId: id('mst', 1), channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-10-02T00:00:00.000Z' } },
    { merchantSiteId: id('mst', 2), channelType: 'APP', channelIdentifier: 'test.example.app', lifecycle: 'ACTIVE', review: null },
  ],
  contracts: [{
    contractId: id('ctr', 1), createdAt: '2026-10-03T00:00:00.000Z',
    revisions: [{ revisionNo: 1, scopeKind: 'SITES', effectiveFrom: '2026-10-04T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-10-03T01:00:00.000Z', scopeSiteIds: [id('mst', 1)], registeredBy: 'admin:adm_01JCRG0000000000000000RG01' }],
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
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  return api
}

describe('MerchantsScreen (MERCHANT-OPS-001 §7 AC 11 — read-only merchant lookup)', () => {
  it('search sends only the filled criteria and lists the masked registration number, MID count and entitlement revision', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    // 기대값 변경(MERCHANT-CODE-001 MC-2, D-ADMIN-UI-004 ③): "가맹점 ID" 검색란이 "가맹점 코드"(단일 기준)로 바뀌었다 — 채운 조건만 보내는 규칙은 상태 조건으로 확인한다.
    expect(screen.queryByLabelText('가맹점 ID')).toBeNull()
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'TERMINATED')
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ lifecycle: 'TERMINATED' })
    const row = await screen.findByRole('row', { name: codeOf(MER1) })
    expect(row).toHaveTextContent('데모 가맹점')
    // MC-2 표시 교체: 가맹점은 코드로(내부 키 mer_… 없음).
    expect(row).toHaveTextContent(codeOf(MER1))
    expect(row).not.toHaveTextContent('mer_')
    // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ③): 사업자 ID(biz_…)는 화면에 보이지 않는다 — 사업자는 상호·마스킹 등록번호로 식별.
    expect(row).not.toHaveTextContent(BIZ1)
    expect(row).toHaveTextContent('데모 사업자')
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
    // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ③): 사업자 ID 검색란이 없어졌다(화면에 사업자 ID를 보이지 않으므로 입력할 값도 없다) — 같은 규칙을 상태 조건으로 확인한다.
    expect(screen.queryByLabelText('사업자 ID')).toBeNull()
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'ACTIVE')
    await search()
    await screen.findByRole('row', { name: codeOf(MER2) })
    // 검색 뒤 입력란을 바꿔도 다음 쪽은 검색한 조건 그대로(조건이 섞이지 않는다).
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'TERMINATED')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { lifecycle: 'ACTIVE', after: MER2 })
    expect(await screen.findByRole('row', { name: codeOf(MER3) })).toBeInTheDocument()
    expect(screen.queryByRole('row', { name: codeOf(MER1) })).toBeNull()
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
    // 기대값 변경(ADMIN-UI-MERCHANT-002 하이픈 자동): maxlength 10 → 없음. 표시에 하이픈이 들어가고(000-00-00000), 붙여넣기가 잘려 10자리로 맞춰지지 않게 한다.
    // 10자리 보장은 보내기 전 형식 검사(다음 시험 — 그대로)와 서버 검사가 한다.
    expect(input).not.toHaveAttribute('maxlength')
    expect(input).toHaveAttribute('autocomplete', 'off')
    // 감사 안내는 줄였지만 유지한다(보안상 필요한 안내 — D-ADMIN-UI-004 설명 최소화의 예외).
    expect(screen.getByText(/검색은 매번 감사 기록/)).toBeInTheDocument()
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
    // 하이픈은 화면이 다시 넣는다 — 숫자 9자리라 여전히 보내지 않는다.
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('123-45-6789')
    await search()
    expect(api.merchantSearch).not.toHaveBeenCalled()
    await userEvent.clear(screen.getByLabelText('사업자등록번호'))
    await typeIn('사업자등록번호', '12345678901')
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
    expect(await screen.findByRole('row', { name: codeOf(MER1) })).toHaveTextContent(MASKED)
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.innerHTML).not.toContain(BRN)
    expect(container.innerHTML).not.toContain('123-45-67890')
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain(BRN)
    expect(log).not.toHaveBeenCalled()
    // 기대값 변경(ADMIN-UI-MERCHANT-002): 사업자 ID를 화면에 보이지 않으므로 "사업자 ID로 다시 검색" 안내 대신 "다음"이 그 결과의 사업자로 이어 찾는다.
    // 번호는 다시 보내지 않는다(화면 상태에 번호 없음 — 그대로) — 다음 쪽 조건은 결과에 이미 있던 내부 사업자 키(businessId)뿐.
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { businessId: BIZ1, after: MER1 })
    expect(JSON.stringify(vi.mocked(api.merchantSearch).mock.calls[1])).not.toContain(BRN)
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
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    expect(api.merchantDetail).toHaveBeenCalledWith(MER1)

    const basic = await screen.findByRole('region', { name: '기본정보' })
    expect(basic).toHaveTextContent('데모 가맹점')
    // 기대값 변경(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ③): 상세에도 사업자 ID는 보이지 않는다(상호·마스킹 번호로 식별).
    expect(basic).not.toHaveTextContent(BIZ1)
    expect(basic).toHaveTextContent('데모 사업자')
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

describe('MerchantsScreen — MERCHANT-CODE-001 MC-2 code display and code search (§3.8, D-ADMIN-UI-004 ③)', () => {
  it('MC-2-3 code search: "가맹점 코드" sends {merchantCode} only (exact, unchanged) and lists the one match by its code', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [{ ...item(MER1, '데모 가맹점'), matchedRetiredCode: false }], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('가맹점 코드', codeOf(MER1))
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ merchantCode: codeOf(MER1) })
    const row = await screen.findByRole('row', { name: codeOf(MER1) })
    expect(row).toHaveTextContent(codeOf(MER1))
    expect(row).not.toHaveTextContent('mer_')
  })

  it('MC-2-3 a malformed code (no normalisation — upper case, too short, spaces) or a code combined with another criterion is refused on screen without a call', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    for (const bad of ['ShopABCD12', 'abcde', ' shop00001 ']) {
      await userEvent.clear(screen.getByLabelText('가맹점 코드'))
      await typeIn('가맹점 코드', bad)
      await search()
      expect(screen.getByText('가맹점 코드는 영문 소문자로 시작, 영문 소문자·숫자 6~14자입니다.')).toBeInTheDocument()
    }
    await userEvent.clear(screen.getByLabelText('가맹점 코드'))
    await typeIn('가맹점 코드', 'shopabcd12')
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'ACTIVE')
    await search()
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(상태·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('상태'), '')
    await typeIn('사업자등록번호', '1234567890')
    await search()
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(상태·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
    expect(api.merchantSearch).not.toHaveBeenCalled()
  })

  it('MC-2-3 DOM: list, detail header, basic info and the merchant row of the history show the code — no mer_ in the screen text or accessible names; other aggregate keys stay', async () => {
    const merchantEntry: MerchantHistoryEntry = { ...entry(20, 'REGISTERED'), operation: 'RegisterMerchant', aggregateType: 'Merchant', aggregateId: MER1 }
    await openDetail({
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [merchantEntry, entry(21, 'SUSPENDED')], nextCursor: null })),
    })
    const history = await screen.findByRole('region', { name: '변경 이력' })
    expect(await within(history).findByRole('row', { name: '20' })).toHaveTextContent(codeOf(MER1))
    expect(within(history).getByRole('row', { name: '21' })).toHaveTextContent(id('cra', 1))
    expect(screen.getByRole('region', { name: '기본정보' })).toHaveTextContent(codeOf(MER1))
    expect(screen.getByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(MER1)}`) })).toBeInTheDocument()
    // 재리뷰 MAJOR-1: DOM 전체 — 텍스트와 모든 속성(value·placeholder·aria-*·title·data-* 포함, innerHTML) 및 입력란 value 프로퍼티에 mer_ 0건.
    expect(document.body.innerHTML).not.toContain('mer_')
    for (const el of Array.from(document.body.querySelectorAll('*'))) {
      for (const attr of Array.from(el.attributes)) expect(`${el.tagName}@${attr.name}=${attr.value}`).not.toContain('mer_')
      if (el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement) expect(el.value).not.toContain('mer_')
    }
  })

  it('ADMIN-HISTORY-KEYS-001 T-HK-2/3: business and merchant history rows show name · masked number / name · code with Korean target names — no biz_/mer_ or plaintext number anywhere; other targets keep their identifier', async () => {
    const rows: MerchantHistoryEntry[] = [
      { ...entry(40, 'REGISTERED'), operation: 'RegisterBusiness', aggregateType: 'Business', aggregateId: BIZ1 },
      { ...entry(41, 'REGISTERED'), operation: 'RegisterMerchant', aggregateType: 'Merchant', aggregateId: MER1 },
      { ...entry(42, 'REGISTERED'), operation: 'RegisterMerchantSite', aggregateType: 'MerchantSite', aggregateId: id('site', 1) },
      entry(43, 'SUSPENDED'),
    ]
    await openDetail({ merchantDetail: vi.fn(async () => detail), merchantHistory: vi.fn(async () => ({ items: rows, nextCursor: null })) })
    const history = await screen.findByRole('region', { name: '변경 이력' })
    const business = await within(history).findByRole('row', { name: '40' })
    expect(within(business).getByText('사업자', { exact: true })).toBeInTheDocument()
    expect(business).toHaveTextContent('Business')
    expect(business).toHaveTextContent(`데모 사업자 · ${MASKED}`)
    const merchant = within(history).getByRole('row', { name: '41' })
    expect(within(merchant).getByText('가맹점', { exact: true })).toBeInTheDocument()
    expect(merchant).toHaveTextContent(`데모 가맹점 · ${codeOf(MER1)}`)
    expect(within(history).getByRole('row', { name: '42' })).toHaveTextContent(id('site', 1))
    const route = within(history).getByRole('row', { name: '43' })
    expect(route).toHaveTextContent('카드사 Route 개통')
    expect(route).toHaveTextContent(id('cra', 1))
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, BRN)).toEqual([])
    expect(domOccurrences(document.body, '123-45-67890')).toEqual([])
  })

  it('MC-2b DOM (D-MCODE-003 ②): a registration-number search list and the detail render no biz_ anywhere (text, attributes, input values) and never the plaintext number — the business is name + masked number; the internal key goes only to calls', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })),
      merchantDetail: vi.fn(async () => detail),
      // ADMIN-HISTORY-KEYS-001 MINOR-2: fixture 보강 — 빈 이력으로는 사업자 이력 행의 biz_ 노출을 못 잡았다(단언은 그대로).
      merchantHistory: vi.fn(async () => ({ items: [{ ...entry(30, 'REGISTERED'), operation: 'RegisterBusiness', aggregateType: 'Business', aggregateId: BIZ1 }], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('사업자등록번호', BRN)
    await search()
    const row = await screen.findByRole('row', { name: codeOf(MER1) })
    expect(row).toHaveTextContent('데모 사업자')
    expect(row).toHaveTextContent(MASKED)
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, BRN)).toEqual([])
    expect(domOccurrences(document.body, '123-45-67890')).toEqual([])
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('region', { name: '기본정보' })).toHaveTextContent(MASKED)
    expect(screen.getByRole('region', { name: '기본정보' })).toHaveTextContent('데모 사업자')
    // ADMIN-HISTORY-KEYS-001 코드 리뷰 MINOR-1: 이력 행이 그려진 뒤에 단언한다(대기만 추가 — 단언 무변경).
    await within(screen.getByRole('region', { name: '변경 이력' })).findByRole('row', { name: '30' })
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(domOccurrences(document.body, BRN)).toEqual([])
    expect(domOccurrences(document.body, '123-45-67890')).toEqual([])
    expect(api.merchantDetail).toHaveBeenCalledWith(MER1)
  })
})
