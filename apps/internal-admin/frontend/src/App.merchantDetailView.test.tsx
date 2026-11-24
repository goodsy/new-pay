import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import type { AdminApi, MerchantDetail, MerchantListItem, Me } from './api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from './test/fakeApi'
import { domOccurrences } from './test/domScan'

/**
 * MERCHANT-DETAIL-VIEW-001(사용자 요청 2026-11-24): 가맹점 조회의 목록 ↔ 상세 화면 전환이 App에서 사업자 조회와 같은 규칙으로 동작한다.
 * - 가맹점 조회 화면 상태는 App 메모리(merchantsMemory)에 있다 — 탭(가맹점 ↔ 사업자)·사이드 메뉴로 다시 들어오면 기억한 상세 화면을 다시 열고, 상세 문서를 다시 읽지 않는다.
 * - 하위 화면(가맹점 등록)의 '목록으로'와 그 화면에서 부모 탭(가맹점) 클릭은 목록을 연다(showListOf — 사업자 조회 reviewFix1 MINOR-1과 같은 규칙).
 * - URL·hash·브라우저 저장소는 쓰지 않는다(세션 경계의 메모리 새로 만들기는 revisionMemory와 같은 자리 — App.tsx signedIn·sessionEnded·logout).
 */
const user = userEvent.setup({ delay: null })
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const CODE1 = 'shop000001'
const ITEM: MerchantListItem = {
  merchantId: MER1, merchantCode: CODE1, merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
/** Named Fixture: Site·계약·MID 없는 갓 등록한 가맹점(상세 화면 전환만 본다). */
const DETAIL: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: CODE1, name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
  sites: [], contracts: [], accounts: [], limits: [], propagation: null, pendingApprovals: [],
}

const OPERATOR: Me = { actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] }

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async (): Promise<Me> => OPERATOR),
    // 세션이 끝난 뒤 다시 로그인(비밀번호 → TOTP — Demo 값, 시험 대역).
    password: vi.fn(async () => ({ next: 'TOTP' as const })),
    totp: vi.fn(async () => OPERATOR),
    approvals: vi.fn(async () => ({ approvals: [] })),
    reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
    accounts: vi.fn(async () => ({ accounts: [] })),
    audit: vi.fn(async () => ({ entries: [] })),
    searchBusinesses: vi.fn(async () => ({ items: [], next: null })),
    transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
    merchantSearch: vi.fn(async () => ({ items: [ITEM], nextCursor: null })),
    merchantDetail: vi.fn(async () => DETAIL),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<App api={api} />)
  return api
}
const sideMenu = () => within(screen.getByRole('navigation', { name: '메뉴' }))
const openMenu = async () => user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
const tab = (name: string) => screen.getByRole('tab', { name })
const detailHeading = () => screen.queryByRole('heading', { name: new RegExp(`가맹점 상세 ${CODE1}`) })
const merchantList = () => screen.queryByRole('table', { name: '가맹점 목록' })

async function openDetail() {
  await openMenu()
  await user.click(screen.getByRole('button', { name: '검색' }))
  await user.click(within(await screen.findByRole('row', { name: CODE1 })).getByRole('button', { name: '상세' }))
  expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${CODE1}`) })).toBeInTheDocument()
  expect(merchantList()).toBeNull()
}

describe('App — 가맹점 list ↔ detail screen follows the 사업자 조회 rules (MERCHANT-DETAIL-VIEW-001)', { timeout: 20_000 }, () => {
  it('tab round trip (가맹점 → 사업자 → 가맹점) reopens the remembered detail without reading it again; "목록으로" shows the kept list', async () => {
    const api = signedIn()
    await openDetail()
    await user.click(tab('사업자'))
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
    expect(detailHeading()).toBeNull()
    await user.click(tab('가맹점'))
    expect(detailHeading()).toBeInTheDocument()
    expect(merchantList()).toBeNull()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)

    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(merchantList()).toBeInTheDocument()
    expect(screen.getByRole('row', { name: CODE1 })).toBeInTheDocument()
    expect(api.merchantSearch).toHaveBeenCalledTimes(1)
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    expect(window.location.hash).toBe('')
    expect(storageIsEmpty()).toBe(true)
  })

  it('side menu (운영 홈 → 사업자·가맹점) also reopens the remembered detail', async () => {
    const api = signedIn()
    await openDetail()
    await user.click(sideMenu().getByRole('button', { name: '운영 홈' }))
    expect(detailHeading()).toBeNull()
    await openMenu()
    expect(detailHeading()).toBeInTheDocument()
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
  })

  it('a remembered detail + 가맹점 등록 (menu search): "목록으로" and the parent 가맹점 tab open the list, not the remembered detail', async () => {
    signedIn()
    await openDetail()
    const openRegistration = async () => {
      await user.click(screen.getByRole('button', { name: '메뉴 검색' }))
      await user.type(within(screen.getByRole('dialog', { name: '메뉴 검색' })).getByRole('searchbox', { name: '메뉴 검색' }), '가맹점 등록')
      await user.click(within(screen.getByRole('list', { name: '메뉴 검색 결과' })).getByRole('button', { name: /가맹점 등록/ }))
      expect(await screen.findByRole('list', { name: '등록 단계' })).toBeInTheDocument()
    }
    await openRegistration()
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(merchantList()).toBeInTheDocument()
    expect(detailHeading()).toBeNull()

    await user.click(within(screen.getByRole('row', { name: CODE1 })).getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${CODE1}`) })).toBeInTheDocument()
    await openRegistration()
    await user.click(tab('가맹점'))
    expect(merchantList()).toBeInTheDocument()
    expect(detailHeading()).toBeNull()
  })

  /** 다시 로그인(시험 대역 — 비밀번호 → TOTP). */
  async function signInAgain() {
    await user.type(await screen.findByLabelText('로그인 이름'), 'demo-operator')
    await user.type(screen.getByLabelText('비밀번호'), 'pw')
    await user.click(screen.getByRole('button', { name: '다음' }))
    await user.type(await screen.findByLabelText('인증 코드(6자리)'), '123456')
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await screen.findByRole('navigation', { name: '메뉴' })
  }
  /** 조건(가맹점명)을 넣고 검색 → 상세. */
  async function openDetailWithCriteria() {
    await openMenu()
    await user.type(screen.getByLabelText('가맹점명'), '데모')
    await user.click(screen.getByRole('button', { name: '검색' }))
    await user.click(within(await screen.findByRole('row', { name: CODE1 })).getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('heading', { name: new RegExp(`가맹점 상세 ${CODE1}`) })).toBeInTheDocument()
  }
  /** 세션 경계 뒤 가맹점 탭: 목록 화면(상세 없음), 결과 표 없음, 검색 조건 비어 있음, 상세 추가 조회 없음. */
  async function expectFreshMerchantList(api: AdminApi) {
    await openMenu()
    expect(tab('가맹점')).toHaveAttribute('aria-selected', 'true')
    expect(detailHeading()).toBeNull()
    expect(screen.queryByRole('button', { name: '목록으로' })).toBeNull()
    expect(merchantList()).toBeNull()
    expect(screen.getByLabelText('가맹점명')).toHaveValue('')
    expect(screen.getByLabelText('가맹점 코드')).toHaveValue('')
    expect(screen.getByLabelText('상태')).toHaveValue('')
    expect(api.merchantDetail).toHaveBeenCalledTimes(1)
    expect(api.merchantSearch).toHaveBeenCalledTimes(1)
  }

  it('review MINOR-1 (a): logout drops the remembered detail, results and criteria — after signing in again 가맹점 is an empty list screen', async () => {
    const api = signedIn()
    await openDetailWithCriteria()
    await user.click(screen.getByRole('button', { name: '로그아웃' }))
    expect(api.logout).toHaveBeenCalledTimes(1)
    await signInAgain()
    await expectFreshMerchantList(api)
    expect(storageIsEmpty()).toBe(true)
  })

  it('review MINOR-1 (b): a SESSION_EXPIRED response drops the remembered detail, results and criteria the same way', async () => {
    const api = signedIn({
      // 상세를 연 뒤 사업자 탭의 첫 목록 읽기에서 세션 만료(401) — 그 뒤 다시 로그인하면 정상.
      searchBusinesses: vi.fn()
        .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
        .mockResolvedValue({ items: [], next: null }),
    })
    await openDetailWithCriteria()
    await user.click(tab('사업자'))
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
    await signInAgain()
    await expectFreshMerchantList(api)
    expect(storageIsEmpty()).toBe(true)
  })
})
