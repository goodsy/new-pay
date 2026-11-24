import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantHistoryEntry, MerchantListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import { expectDetailRows, panelDetailList } from '../test/detailRows'

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
  // 기대값 변경(D-ADMIN-UI-009 ② 사용자 결정 2026-11-24 "기술 정보 접기"): 목록의 '권리 revision' 열은 없앴다 — 값은 상세의 접힌 "기술 정보"에 있다(MerchantsScreen.techInfo.test).
  it('search sends only the filled criteria and lists the masked registration number and MID count (no entitlement revision column)', async () => {
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
    expect(row.querySelector('[data-col="entitlementRevision"]')).toBeNull()
    expect(within(screen.getByRole('table', { name: '가맹점 목록' })).queryByRole('columnheader', { name: '권리 revision' })).toBeNull()
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

    // D-ADMIN-UI-009 ②: 권리 전파는 기본 접힘 "기술 정보" 안 — 열면 같은 값(기대값 그대로).
    const techInfo = screen.getByRole('group', { name: '기술 정보' })
    expect(techInfo).not.toHaveAttribute('open')
    await userEvent.click(within(techInfo).getByText('기술 정보'))
    expect(techInfo).toHaveAttribute('open')
    const propagation = within(techInfo).getByRole('region', { name: '권리 전파 상태' })
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
    // 기대값 변경(MERCHANT-IA-001 B1 — 명세 rev 0.2 §3.8·§7 B1): '연산'(영문 원문) 열을 뺐다. 리뷰 MINOR-1: '변경' 열 셀을 지정해 본다 — 열 순서 순번·시각·대상·변경·행위자,
    // Fixture의 'REGISTERED'는 이력 변경 종류 이름표에 없는 값이라 원문 그대로(changeTypeLabel 폴백).
    const first = await within(history).findByRole('row', { name: '10' })
    expect(within(first).getAllByRole('cell')[3]).toHaveTextContent(/^REGISTERED$/)
    expect(first).not.toHaveTextContent('RegisterCardRouteActivation')
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
    // MERCHANT-NAME-SEARCH-001 MNS-3 기대값 변경(사용자 요청 가맹점명 조건)
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(가맹점명·상태·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('상태'), '')
    await typeIn('사업자등록번호', '1234567890')
    await search()
    // MERCHANT-NAME-SEARCH-001 MNS-3 기대값 변경(사용자 요청 가맹점명 조건)
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(가맹점명·상태·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
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

/**
 * LAYOUT-ROLLOUT B6(사용자 결정 2026-11-19 — "사업자 조회 상세보기처럼 다른 화면도 일관성 있게"): 바깥 Panel(.card > .cardtop h4 + .cardbody)은 그대로,
 * 안쪽 정의 목록만 사업자 상세 DetailRow 줄(이름 140px 열 · 줄 사이 얇은 선 · 640px 미만 위아래). 모양만 — 값·이름 글자·업무 호출은 위 시험이 지킨다.
 */
describe('LAYOUT-ROLLOUT B6: 가맹점 상세 정의 목록 = 사업자 상세 줄(DetailRow)', () => {
  // 기대값 변경(D-ADMIN-UI-009 ② "기술 정보 접기"): 기본정보의 '권리 revision' 줄은 접힌 "기술 정보"의 '가맹점 권리' Panel로 옮겼다(값 그대로). 권리 전파 상태는 같은 Panel 모양으로 그 안에.
  it('기본정보 · 가맹점 권리 · 권리 전파 상태는 바깥 Panel 그대로, 안쪽은 140px 이름 열 줄(style 속성 없음)', async () => {
    await openDetail({ merchantDetail: vi.fn(async () => detail), merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })) })
    const basics = await screen.findByRole('region', { name: '기본정보' })
    expectDetailRows(panelDetailList(basics, '기본정보'), ['가맹점', '가맹점 상태', '등록일', '사업자', '사업자등록번호(마스킹)', '사업자 상태'])
    const techInfo = screen.getByRole('group', { name: '기술 정보' })
    await userEvent.click(within(techInfo).getByText('기술 정보'))
    expectDetailRows(panelDetailList(within(techInfo).getByRole('region', { name: '가맹점 권리' }), '가맹점 권리'), ['권리 revision'])
    const propagation = within(techInfo).getByRole('region', { name: '권리 전파 상태' })
    expectDetailRows(panelDetailList(propagation, '권리 전파 상태'), ['발행', '결제 서비스 수신', '효력'])
    // 값은 그대로(상태 배지 · 마스킹 번호 · 효력 확인 전).
    expect(within(basics).getAllByText('활성').length).toBeGreaterThan(0)
    expect(within(basics).getByText(MASKED)).toHaveClass('font-mono')
    expect(within(propagation).getByText('효력 확인 전')).toBeInTheDocument()
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  // LAYOUT-ROLLOUT B6 리뷰 MINOR-2 기대값 변경(이번 B6에서 새로 만든 시험): 표 전체 nowrap이면 긴 채널 식별값(최대 253자)이 표를 넓혀 상태·심사 결과 열이
  // 데스크톱에서도 가로 스크롤 뒤로 숨는다 → 짧은 값 칸(Site ID·채널 종류·상태)만 nowrap, 식별값은 줄바꿈 허용 + 칸 최소 폭(16ch)으로 한 글자씩 쪼개짐만 막는다.
  it('390px: Site 표는 가로 스크롤 — 짧은 값 칸만 줄바꿈 없음, 채널 식별값은 줄바꿈 허용 + 최소 폭(한 글자씩 세로로 쪼개지지 않음)', async () => {
    await openDetail({ merchantDetail: vi.fn(async () => detail), merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })) })
    const table = await screen.findByRole('table', { name: 'Site 목록' })
    expect(table).not.toHaveClass('whitespace-nowrap')
    expect(table.parentElement).toHaveClass('overflow-x-auto')
    const cells = within(within(table).getByRole('row', { name: id('mst', 1) })).getAllByRole('cell')
    expect(cells[0]).toHaveClass('whitespace-nowrap') // Site ID
    expect(cells[0]).toHaveTextContent(id('mst', 1))
    expect(cells[2]).toHaveClass('whitespace-nowrap') // 상태
    expect(cells[1]).not.toHaveClass('whitespace-nowrap') // 채널 칸 전체는 줄바꿈 허용
    const channel = cells[1]!.querySelector(':scope > span.flex-col')!
    expect(channel.children[0]).toHaveClass('whitespace-nowrap') // 채널 종류
    const identifier = within(cells[1]!).getByText('shop.example.test')
    expect(identifier.parentElement).toHaveClass('min-w-[16ch]')
    expect(identifier.parentElement).not.toHaveClass('whitespace-nowrap')
    expect(identifier.parentElement!.parentElement).toBe(channel)
  })
})

/**
 * MERCHANT-NAME-SEARCH-001 MNS-3(사용자 요청 2026-11-19 "가맹점조회에서 조건이 가맹점명도 추가해줘", 명세 §3·§4): 가맹점명 앞부분 일치 조건.
 * 화면은 입력 원문 그대로 보낸다(trim·정규화 없음 — 형식 위반은 서버 400을 기존 고정 문구로, 사업자 조회 화면과 같은 방식). 코드 검색과는 함께 쓰지 않는다.
 */
describe('MerchantsScreen — MERCHANT-NAME-SEARCH-001 MNS-3 가맹점명 조건', () => {
  /** 앞뒤 공백 포함 원문(화면은 고치지 않는다 — 서버가 400으로 거절할 값도 그대로 보낸다). 화면 다른 곳에 우연히 나오지 않는 글자 조합. */
  const RAW_NAME = ' Zq가맹%_! '

  it('MNS-3-1 가맹점명 입력란은 코드 다음·상태 앞(같은 필터 줄), 입력하면 {displayNamePrefix}만 원문 그대로 보낸다', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    const input = screen.getByLabelText('가맹점명')
    expect(input).toHaveAttribute('placeholder', '가맹점명 (앞부분 일치)')
    expect(input).toHaveAttribute('autocomplete', 'off')
    expect(input).toHaveAttribute('spellcheck', 'false')
    const form = input.closest('form')!
    expect(form).toHaveClass('filters')
    const controls = Array.from(form.querySelectorAll('input, select')).map((el) => el.getAttribute('aria-label'))
    expect(controls.indexOf('가맹점명')).toBe(controls.indexOf('가맹점 코드') + 1)
    expect(controls.indexOf('상태')).toBe(controls.indexOf('가맹점명') + 1)
    await typeIn('가맹점명', RAW_NAME)
    await search()
    expect(api.merchantSearch).toHaveBeenCalledTimes(1)
    expect(api.merchantSearch).toHaveBeenCalledWith({ displayNamePrefix: RAW_NAME })
    expect(await screen.findByRole('row', { name: codeOf(MER1) })).toBeInTheDocument()
  })

  it('MNS-3-2 가맹점명 + 상태 → 둘 다 보낸다(AND — 서버 결정)', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await typeIn('가맹점명', '데모')
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'ACTIVE')
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ displayNamePrefix: '데모', lifecycle: 'ACTIVE' })
  })

  it('MNS-3-3 가맹점 코드 + 가맹점명 → 호출 없이 바뀐 combined 문구', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await typeIn('가맹점 코드', 'shopabcd12')
    await typeIn('가맹점명', '데모')
    await search()
    expect(api.merchantSearch).not.toHaveBeenCalled()
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(가맹점명·상태·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByLabelText('가맹점 코드')).toHaveAttribute('aria-invalid', 'true')
  })

  it('MNS-3-4 "다음"은 검색한 가맹점명 그대로 {displayNamePrefix, after}(검색 뒤 입력을 바꿔도 섞이지 않는다)', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn()
        .mockResolvedValueOnce({ items: [item(MER1, '데모 하나'), item(MER2, '데모 둘')], nextCursor: MER2 })
        .mockResolvedValueOnce({ items: [item(MER3, '데모 셋')], nextCursor: null }),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await typeIn('가맹점명', '데모')
    await search()
    await screen.findByRole('row', { name: codeOf(MER2) })
    await userEvent.clear(screen.getByLabelText('가맹점명'))
    await typeIn('가맹점명', '다른')
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { displayNamePrefix: '데모', after: MER2 })
    expect(await screen.findByRole('row', { name: codeOf(MER3) })).toBeInTheDocument()
  })

  // MNS-3 리뷰 MINOR-1: 이름 + 등록번호 검색의 다음 쪽 — 이름은 이어지고, 번호 대신 결과의 사업자 키로 이어 찾는다(번호는 다시 보내지 않는다).
  it('MNS-3-4b 가맹점명 + 사업자등록번호 → "다음"은 {displayNamePrefix, businessId, after}(registrationNumber 키 없음)', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn()
        .mockResolvedValueOnce({ items: [item(MER1, '데모 하나')], nextCursor: MER1 })
        .mockResolvedValueOnce({ items: [item(MER2, '데모 둘')], nextCursor: null }),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('가맹점명', '데모')
    await typeIn('사업자등록번호', BRN)
    await search()
    expect(api.merchantSearch).toHaveBeenNthCalledWith(1, { displayNamePrefix: '데모', registrationNumber: BRN })
    await screen.findByRole('row', { name: codeOf(MER1) })
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { displayNamePrefix: '데모', businessId: BIZ1, after: MER1 })
    const second = vi.mocked(api.merchantSearch).mock.calls[1]![0]
    expect(Object.keys(second)).not.toContain('registrationNumber')
    expect(JSON.stringify(second)).not.toContain(BRN)
    expect(await screen.findByRole('row', { name: codeOf(MER2) })).toBeInTheDocument()
  })

  it('MNS-3-5 형식 위반 이름(앞뒤 공백)의 400 INVALID_INPUT → 기존 고정 문구, 입력값은 입력란 밖 어디에도 비치지 않는다', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => { throw apiError(400, 'INVALID_INPUT') }) })
    // MNS-3 리뷰 NIT-1: 등록번호 칸이 있는 역할로 그려 코드·등록번호 입력란 value도 함께 본다.
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await typeIn('가맹점명', RAW_NAME)
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ displayNamePrefix: RAW_NAME })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('입력 형식이 올바르지 않습니다.')
    expect(alert.textContent).not.toContain('Zq가맹')
    // 입력란 value(프로퍼티·속성)만 — 문구·목록·속성(aria-*·title 등) 어디에도 없다.
    const where = domOccurrences(document.body, 'Zq가맹')
    expect(where.length).toBeGreaterThan(0)
    for (const place of where) expect(place).toMatch(/\/INPUT\/value$/)
    // MNS-3 리뷰 NIT-1: 그 입력란은 가맹점명 입력란뿐 — 코드·등록번호 입력란 value에는 없다.
    expect(screen.getByLabelText('가맹점명')).toHaveValue(RAW_NAME)
    expect((screen.getByLabelText('가맹점 코드') as HTMLInputElement).value).not.toContain('Zq가맹')
    expect((screen.getByLabelText('사업자등록번호') as HTMLInputElement).value).not.toContain('Zq가맹')
  })

  // MNS-3 리뷰 NIT-2: combined 오류일 때만 가맹점명 입력란도 그 오류 문구를 가리킨다.
  it('MNS-3-3b combined 오류가 있을 때만 가맹점명 입력란 aria-describedby = "<도움말> <코드 안내>"', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    const name = screen.getByLabelText('가맹점명')
    const help = screen.getByText('가맹점명: 앞부분이 같은 이름(영문 대소문자 구분, 앞뒤 공백 없이)')
    const codeNoteId = screen.getByLabelText('가맹점 코드').getAttribute('aria-describedby')!
    expect(codeNoteId).not.toBe('')
    expect(name).toHaveAttribute('aria-describedby', help.id)
    // 형식 오류(코드 혼자)는 가맹점명과 무관 — 연결하지 않는다.
    await typeIn('가맹점 코드', 'abc')
    await search()
    expect(screen.getByText('가맹점 코드는 영문 소문자로 시작, 영문 소문자·숫자 6~14자입니다.')).toBeInTheDocument()
    expect(name).toHaveAttribute('aria-describedby', help.id)
    await userEvent.clear(screen.getByLabelText('가맹점 코드'))
    await typeIn('가맹점 코드', 'shopabcd12')
    await typeIn('가맹점명', '데모')
    await search()
    expect(document.getElementById(codeNoteId)).toHaveTextContent('가맹점 코드 검색은 다른 조건(가맹점명·상태·사업자등록번호)과 함께 쓸 수 없습니다.')
    expect(name).toHaveAttribute('aria-describedby', `${help.id} ${codeNoteId}`)
    // 코드를 비우고 다시 검색하면 오류가 사라지고 연결도 도움말만.
    await userEvent.clear(screen.getByLabelText('가맹점 코드'))
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({ displayNamePrefix: '데모' })
    expect(name).toHaveAttribute('aria-describedby', help.id)
  })

  it('MNS-3-6 조회 안내에 가맹점명 도움말(앞부분 일치·영문 대소문자 구분), 입력란이 그 안내를 가리킨다; style 속성 0', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    const help = screen.getByText('가맹점명: 앞부분이 같은 이름(영문 대소문자 구분, 앞뒤 공백 없이)')
    expect(help.closest('.cardnote')).not.toBeNull()
    expect(help.id).not.toBe('')
    expect(screen.getByLabelText('가맹점명')).toHaveAttribute('aria-describedby', help.id)
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    await typeIn('가맹점명', '데모')
    await search()
    await screen.findByRole('row', { name: codeOf(MER1) })
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })
})
