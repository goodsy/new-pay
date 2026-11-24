import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen, showMerchantListIn } from './MerchantsScreen'
import type { AdminApi, MerchantDetail, MerchantListItem } from '../api/adminApi'
import { createScreenMemory, hasUnresolvedRequest } from '../lib/screenMemory'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * MERCHANT-DETAIL-VIEW-001(사용자 요청 2026-11-24 "기준정보 상세보기 화면 아래가 아닌 화면 전환으로"): 가맹점 상세는 목록 아래에 펼치지 않고
 * 사업자 조회와 같은 목록 ↔ 상세 화면 전환이다. 상세 화면에는 목록·검색이 없고 제목 줄 '← 목록으로'로 돌아가면 검색 조건·결과·다음 쪽 커서가 그대로다(다시 검색하지 않는다).
 * 화면 상태는 [memory](App 메모리)에 두어 탭을 오가도 기억한 화면(상세)을 다시 연다 — 상세 문서가 있으면 다시 읽지 않는다(상세 조회 감사는 실제로 읽을 때만).
 * 결과 불명 잠금(LOCK-UNIFY-001 — revisionMemory)은 목록으로 가도 그대로이고 같은 가맹점을 다시 열면 같은 submissionId 재전송만 허용한다.
 * 가맹점·사업자 내부 키(mer_·biz_)는 메모리에만 — DOM에 없다(C-14·D-ADMIN-UI-004 ③). Demo 식별자, 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MER2 = id('mer', 2)
const MID1 = id('mid', 1)
const ACT1 = id('act', 1)
const SITE1 = id('site', 1)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')

const itemOf = (merchantId: string, name: string): MerchantListItem => ({
  merchantId, merchantCode: codeOf(merchantId), merchantName: name, businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 1, createdAt: '2026-11-01T00:00:00.000Z',
})
/** Named Fixture: MID 활성 + PRIMARY Site1, 결제수단 개통 없음(개통 등록 가능), 계약 없음. */
const detailOf = (merchantId: string, name: string): MerchantDetail => ({
  merchant: { merchantId, merchantCode: codeOf(merchantId), name, lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
  sites: [{ merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null }],
  contracts: [],
  accounts: [{
    merchantAccountId: MID1, lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
    issuedAt: '2026-11-10T00:00:00.000Z',
    assignments: [{ assignmentId: id('asg', 1), merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
    paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [],
  }],
  limits: [], propagation: null, pendingApprovals: [],
})
const ITEM1 = itemOf(MER1, '데모 가맹점')
const ITEM2 = itemOf(MER2, '데모 둘째 가맹점')

function setup(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    // 첫 쪽(ITEM1, 커서 있음) → 다음 쪽(ITEM2, 끝).
    merchantSearch: vi.fn(async (input: { after?: string }) => (input.after === undefined ? { items: [ITEM1], nextCursor: 'c1' } : { items: [ITEM2], nextCursor: null })),
    merchantDetail: vi.fn(async (merchantId: string) => (merchantId === MER2 ? detailOf(MER2, '데모 둘째 가맹점') : detailOf(MER1, '데모 가맹점'))),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  const memory = createScreenMemory()
  const revisionMemory = createScreenMemory()
  let n = 0
  const newSubmissionId = () => `${++n}`.padStart(32, 'e')
  const show = () =>
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval now={NOW} newSubmissionId={newSubmissionId} memory={memory} revisionMemory={revisionMemory} />)
  return { api, memory, revisionMemory, show }
}

const list = () => screen.queryByRole('table', { name: '가맹점 목록' })
const back = () => screen.getByRole('button', { name: '목록으로' })
const openRow = async (merchantId: string) =>
  userEvent.click(within(await screen.findByRole('row', { name: codeOf(merchantId) })).getByRole('button', { name: '상세' }))
const detailHeading = (merchantId: string) => screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${codeOf(merchantId)}`) })
/** 가맹점·사업자 내부 키는 상세 화면 DOM에 없다(MerchantsScreen.test의 MC-2b·ADMIN-UI-MERCHANT-002 단언과 같은 범위 — 계약·MID 식별자 표시는 이 작업 밖이며 그대로). */
const noInternalKeys = () => {
  for (const prefix of ['mer_', 'biz_']) expect(domOccurrences(document.body, prefix)).toEqual([])
}

describe('MerchantsScreen — list ↔ detail screen switch (MERCHANT-DETAIL-VIEW-001)', () => {
  it('"상세" switches to the detail screen: the list, filters and search are hidden; the title row has "← 목록으로"; no internal key in the DOM', async () => {
    const { show } = setup()
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByRole('table', { name: '가맹점 목록' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '목록으로' })).toBeNull()
    await openRow(MER1)

    expect(await detailHeading(MER1)).toBeInTheDocument()
    expect(list()).toBeNull()
    expect(screen.queryByRole('button', { name: '검색' })).toBeNull()
    expect(screen.queryByLabelText('가맹점 코드')).toBeNull()
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
    expect(back()).toHaveTextContent('← 목록으로')
    expect(back().querySelector('span[aria-hidden="true"]')).toHaveTextContent('←')
    expect(back().closest('.mapnote')).not.toBeNull()
    expect(screen.getByRole('heading', { level: 2, name: '가맹점 조회' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: `개통 ${MID1}` })).toBeInTheDocument()
    noInternalKeys()
    expect(storageIsEmpty()).toBe(true)
  })

  it('"← 목록으로" returns to the same list: filters, rows of the page shown and the next-page state stay; nothing is searched again', async () => {
    const { api, show } = setup()
    show()
    await userEvent.type(screen.getByLabelText('가맹점명'), '데모')
    // 기대값 변경(MERCHANT-IA-001 B2 — 사용자 결정 D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다", 명세 §7 B2): 상태 필터가 없어졌다 — 돌아와도 남는 조건은 가맹점명으로 본다.
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(await screen.findByRole('button', { name: '다음' }))
    await openRow(MER2)
    expect(await detailHeading(MER2)).toBeInTheDocument()
    expect(list()).toBeNull()

    await userEvent.click(back())
    expect(list()).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /가맹점 상세/ })).toBeNull()
    expect(screen.getByRole('row', { name: codeOf(MER2) })).toHaveClass('selected')
    expect(screen.queryByRole('row', { name: codeOf(MER1) })).toBeNull()
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
    expect(screen.getByLabelText('가맹점명')).toHaveValue('데모')
    expect(api.merchantSearch).toHaveBeenCalledTimes(2)
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  it('the next-page cursor stays: back on page 1 the "다음" button continues with the same criteria and cursor', async () => {
    const { api, show } = setup()
    show()
    await userEvent.type(screen.getByLabelText('가맹점명'), '데모')
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await detailHeading(MER1)
    await userEvent.click(back())
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(await screen.findByRole('row', { name: codeOf(MER2) })).toBeInTheDocument()
    expect(vi.mocked(api.merchantSearch).mock.calls.map((c) => c[0])).toEqual([{ displayNamePrefix: '데모' }, { displayNamePrefix: '데모', after: 'c1' }])
  })

  it('with the same screen memory (tab switch) the remembered detail reopens without reading it again; "목록으로" then shows the kept list', async () => {
    const { api, show } = setup()
    const first = show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await screen.findByRole('region', { name: `개통 ${MID1}` })
    first.unmount()

    show()
    expect(await detailHeading(MER1)).toBeInTheDocument()
    expect(list()).toBeNull()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantHistory).toHaveBeenCalledTimes(1)
    await userEvent.click(back())
    expect(screen.getByRole('row', { name: codeOf(MER1) })).toBeInTheDocument()
    expect(api.merchantSearch).toHaveBeenCalledTimes(1)
  })

  it('a remembered detail screen whose document never arrived reads it once on return (same rule as 사업자 조회)', async () => {
    const pending = vi.fn(() => new Promise<never>(() => undefined))
    const { api, show } = setup({ merchantDetail: pending })
    const first = show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    expect(await screen.findByText('상세를 불러오는 중입니다.')).toBeInTheDocument()
    first.unmount()
    vi.mocked(api.merchantDetail).mockImplementation(async () => detailOf(MER1, '데모 가맹점'))

    show()
    expect(await detailHeading(MER1)).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(2)
    expect(vi.mocked(api.merchantDetail).mock.calls.map((c) => c[0])).toEqual([MER1, MER1])
  })

  it('review NIT-1: leaving while the history loads and that request then fails — on return only the history is read again (no second detail read / audit)', async () => {
    let failHistory: (e: unknown) => void = () => undefined
    const merchantHistory = vi.fn()
      .mockImplementationOnce(() => new Promise((_, reject) => { failHistory = reject }))
      .mockResolvedValue({ items: [], nextCursor: null })
    const { api, show } = setup({ merchantHistory })
    const first = show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await detailHeading(MER1)
    expect(await screen.findByText('이력을 불러오는 중입니다.')).toBeInTheDocument()
    first.unmount()
    failHistory(apiError(503, 'MASTER_UNAVAILABLE'))
    await Promise.resolve()

    show()
    expect(await detailHeading(MER1)).toBeInTheDocument()
    expect(await screen.findByRole('table', { name: '변경 이력 목록' })).toBeInTheDocument()
    expect(merchantHistory).toHaveBeenCalledTimes(2)
    expect(merchantHistory.mock.calls.map((c) => c[0])).toEqual([MER1, MER1])
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  it('showMerchantListIn changes only the remembered view (등록 화면 "목록으로"·부모 탭) — results and the open detail stay', async () => {
    const { memory, show } = setup()
    const first = show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await detailHeading(MER1)
    first.unmount()
    const page = memory.read('lookup.page')
    showMerchantListIn(memory)
    expect(memory.read('lookup.view')).toEqual({ kind: 'list' })
    expect(memory.read('lookup.page')).toBe(page)

    show()
    expect(screen.getByRole('table', { name: '가맹점 목록' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: codeOf(MER1) })).toHaveClass('selected')
    expect(screen.queryByRole('heading', { name: /가맹점 상세/ })).toBeNull()
  })

  it('an unknown "개통 등록" on the detail: "목록으로" keeps the lock (hasUnresolvedRequest), reopening the merchant offers only the same resend with the same submissionId', async () => {
    const one = '1'.padStart(32, 'e')
    const registerPaymentMethodActivation = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: ACT1, replayed: true })
    const { revisionMemory, show } = setup({ registerPaymentMethodActivation })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    let area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' })

    await userEvent.click(back())
    expect(list()).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: `개통 ${MID1}` })).toBeNull()
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)

    await openRow(MER1)
    area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    expect(within(area).queryByRole('button', { name: '개통 등록(카드)' })).toBeNull()
    await userEvent.click(within(area).getByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    expect(registerPaymentMethodActivation.mock.calls.map((c) => c[0])).toEqual(
      Array(2).fill({ submissionId: one, merchantAccountId: MID1, paymentMethod: 'CARD' }),
    )
    expect(hasUnresolvedRequest(revisionMemory)).toBe(false)
    expect(storageIsEmpty()).toBe(true)
  })

  it('a "개통 등록" still in flight: "목록으로" is allowed, the busy state stays a result-check (hasUnresolvedRequest) and the reopened detail cannot send a new one', async () => {
    const registerPaymentMethodActivation = vi.fn(() => new Promise<never>(() => undefined))
    const { revisionMemory, show } = setup({ registerPaymentMethodActivation })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    let area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)

    await userEvent.click(back())
    expect(list()).toBeInTheDocument()
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)
    await openRow(MER1)
    area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    expect(within(area).getByRole('button', { name: '개통 등록(카드)' })).toBeDisabled()
    expect(registerPaymentMethodActivation).toHaveBeenCalledTimes(1)
  })

  it('a screen without injected memory (no App) starts from the list on each mount, as before', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [ITEM1], nextCursor: null })),
      merchantDetail: vi.fn(async () => detailOf(MER1, '데모 가맹점')),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    const first = render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} now={NOW} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await detailHeading(MER1)
    first.unmount()
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} now={NOW} />)
    expect(screen.getByRole('button', { name: '검색' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: /가맹점 상세/ })).toBeNull()
  })
})
