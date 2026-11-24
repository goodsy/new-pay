import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { activationChecklist } from '../components/MerchantActivationArea'
import { serviceOpeningTimeline } from '../components/ServiceOpeningTimeline'
import { createScreenMemory, hasUnresolvedRequest } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, fakeApi } from '../test/fakeApi'
import { openMerchantTab } from '../test/merchantDetailTabs'

/**
 * MERCHANT-IA-001 B4(명세 rev 0.3a §4.3·§7 B4 — 사용자 결정 D-ADMIN-UI-010 ①·④, Q-IA-4b, D-ADMIN-UI-011 ①):
 * - 머리 요약 + 탭 6개(개요 / Site·계약 / MID·개통 / 한도 / 변경 이력 / 기술 정보) — tablist·tab·tabpanel, aria-controls, 좌우·Home·End, 패널은 hidden(언마운트 없음).
 * - MID 선택은 상세 맨 위, **MID 것만 따라감**: MID·개통 탭과 한도의 MID 범위 행만 고른 MID, 나머지는 "가맹점 공통". 여러 MID일 때 Site 심사 "대표"는 고른 MID 기준.
 * - 고른 탭·MID는 화면 메모리(URL 불변), 다른 가맹점을 열면 개요·기본 MID.
 * - 결과 불명 잠금이 있으면 머리 "결과 확인 필요" + 해당 탭 표시, 탭을 바꿔도 잠긴 폼이 남는다.
 * - 결제 준비 상태 줄 = 기존 serviceOpeningTimeline·activationChecklist 값(새 판정 없음, 참고).
 * Demo 식별자, 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MER2 = id('mer', 2)
const MID1 = id('mid', 1)
const MID2 = id('mid', 2)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')
const TAB_NAMES = ['개요', 'Site·계약', 'MID·개통', '한도', '변경 이력', '기술 정보']

const listItem = (merchantId: string, name: string): MerchantListItem => ({
  merchantId, merchantCode: codeOf(merchantId), merchantName: name, businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 2, createdAt: '2026-11-01T00:00:00.000Z',
})
type Account = MerchantDetail['accounts'][number]
const account = (mid: string, primarySite: string, patch: Partial<Account> = {}): Account => ({
  merchantAccountId: mid, lifecycle: 'ACTIVE', isDefault: mid === MID1, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: mid === MID1 ? 1 : 2,
  issuedAt: '2026-11-10T00:00:00.000Z',
  assignments: [{ assignmentId: id('asg', mid === MID1 ? 1 : 2), merchantSiteId: primarySite, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
  paymentMethods: [], cardRoutes: [], siteConfirmations: [],
  currencies: [{ enablementId: id('cur', mid === MID1 ? 1 : 2), currencyCode: 'KRW', effectiveFrom: '2026-11-10T01:00:00.000Z', effectiveTo: null, endedAt: null }],
  ...patch,
})
/** Named Fixture: 계약 승인 + MID 둘(MID1 기본·대표 Site1, MID2·대표 Site2), 한도 셋(서비스 약정 기본·MID1 개별·MID2 개별). */
function detail(merchantId = MER1, patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId, merchantCode: codeOf(merchantId), name: merchantId === MER1 ? '데모 가맹점' : '둘째 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'one.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
      { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'two.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: 'admin:adm_SR' },
    ],
    contracts: [{
      contractId: id('ctr', 1), createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-09T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-09T16:00:00.000Z', scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
      agreements: [{ agreementId: id('csa', 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [account(MID1, SITE1), account(MID2, SITE2)],
    limits: [
      { policyId: id('tlp', 1), scopeKind: 'CSA_DEFAULT', agreementId: id('csa', 1), merchantAccountId: null, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 1000000, dailyKind: 'UNLIMITED', dailyAmount: null, effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null, endedAt: null },
      { policyId: id('tlp', 2), scopeKind: 'MID_OVERRIDE', agreementId: null, merchantAccountId: MID1, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 100000, dailyKind: 'UNLIMITED', dailyAmount: null, effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null, endedAt: null },
      { policyId: id('tlp', 3), scopeKind: 'MID_OVERRIDE', agreementId: null, merchantAccountId: MID2, currencyCode: 'KRW', perTxKind: 'LIMITED', perTxAmount: 200000, dailyKind: 'UNLIMITED', dailyAmount: null, effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null, endedAt: null },
    ],
    propagation: null, pendingApprovals: [],
    ...patch,
  }
}

function setup(details: Record<string, MerchantDetail> = { [MER1]: detail() }, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: Object.keys(details).map((m) => listItem(m, details[m]!.merchant.name)), nextCursor: null })),
    merchantDetail: vi.fn(async (m: string) => details[m]!),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  const memory = createScreenMemory()
  const revisionMemory = createScreenMemory()
  let n = 0
  const show = () => render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval memory={memory} revisionMemory={revisionMemory} now={NOW} newSubmissionId={() => `${++n}`.padStart(32, 'b')} />)
  return { api, memory, revisionMemory, show }
}
async function openRow(merchantId: string) {
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(merchantId) })).getByRole('button', { name: '상세' }))
  await screen.findByRole('tablist', { name: '가맹점 상세 보기' })
}
const tabs = () => within(screen.getByRole('tablist', { name: '가맹점 상세 보기' })).getAllByRole('tab')
const panelOf = (name: string) => document.getElementById(screen.getByRole('tab', { name }).getAttribute('aria-controls')!)!
const PREFIXES = ['mer_', 'biz_', 'site_', 'ctr_', 'csa_', 'act_', 'apr_', 'sac_', 'cra_', 'tlp_', 'pas_', 'cur_', 'asg_']

describe('MERCHANT-IA-001 B4 — 가맹점 상세 탭', () => {
  it('tablist "가맹점 상세 보기": 탭 6개(개요 선택), tab ↔ tabpanel 연결, 고른 패널만 보이고 나머지는 hidden(DOM에는 남음)', async () => {
    const { show } = setup()
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    expect(tabs().map((t) => t.getAttribute('aria-label'))).toEqual(TAB_NAMES)
    expect(tabs().map((t) => t.getAttribute('aria-selected'))).toEqual(['true', 'false', 'false', 'false', 'false', 'false'])
    expect(tabs().map((t) => t.tabIndex)).toEqual([0, -1, -1, -1, -1, -1])
    for (const tab of tabs()) {
      const panel = document.getElementById(tab.getAttribute('aria-controls')!)!
      expect(panel).toHaveAttribute('role', 'tabpanel')
      expect(panel).toHaveAttribute('aria-labelledby', tab.id)
      expect(panel.hidden).toBe(tab.getAttribute('aria-selected') !== 'true')
    }
    // 숨은 탭 내용도 DOM에 있다(언마운트 없음) — 접근성 트리에서는 빠진다.
    expect(panelOf('MID·개통').querySelector(`section[aria-label="개통 ${MID1}"]`)).not.toBeNull()
    expect(screen.queryByRole('region', { name: `개통 ${MID1}` })).toBeNull()
  })

  it('키보드: 오른쪽·왼쪽 화살표(끝에서 돌아감)·Home·End로 탭을 옮기고 바로 고르며 초점도 옮긴다', async () => {
    const { show } = setup()
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    tabs()[0]!.focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: 'Site·계약' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'Site·계약' })).toHaveFocus()
    await userEvent.keyboard('{End}')
    expect(screen.getByRole('tab', { name: '기술 정보' })).toHaveFocus()
    expect(screen.getByRole('tab', { name: '기술 정보' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.keyboard('{ArrowRight}')
    expect(screen.getByRole('tab', { name: '개요' })).toHaveFocus()
    await userEvent.keyboard('{ArrowLeft}')
    expect(screen.getByRole('tab', { name: '기술 정보' })).toHaveFocus()
    await userEvent.keyboard('{Home}')
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('region', { name: '서비스 개통 현황' })).toBeInTheDocument()
  })

  it('MID 선택(머리): MID·개통 탭과 한도의 MID 범위 행만 고른 MID를 따른다; Site·계약·변경 이력·기술 정보는 "가맹점 공통"으로 그대로', async () => {
    const { show } = setup()
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    const select = screen.getByLabelText('MID 선택') as HTMLSelectElement
    expect(select.value).toBe(MID1)
    await openMerchantTab('MID·개통')
    expect(screen.getByRole('region', { name: `개통 ${MID1}` })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: `개통 ${MID2}` })).toBeNull()
    await openMerchantTab('한도')
    const limitTexts = () => within(screen.getByRole('table', { name: '한도 목록' })).getAllByRole('row').slice(1).map((r) => r.textContent)
    expect(limitTexts()).toEqual([expect.stringContaining('가맹점 공통'), expect.stringContaining(`MID ${MID1}`)])
    await openMerchantTab('Site·계약')
    const siteRows = () => within(screen.getByRole('table', { name: 'Site 목록' })).getAllByRole('row').map((r) => r.textContent)
    const before = siteRows()
    const primaryRow = () => within(screen.getByRole('table', { name: 'Site 심사' })).getAllByRole('row').slice(1).find((r) => r.textContent?.includes('대표(선택 MID)'))?.getAttribute('aria-label')
    expect(primaryRow()).toBe('심사 웹 · one.example.test')
    expect(panelOf('Site·계약')).toHaveTextContent('가맹점 공통')

    await userEvent.selectOptions(select, MID2)
    expect(siteRows()).toEqual(before)
    // 리뷰 후속(B1 MINOR-3): 대표 표시는 고른 MID의 대표 Site.
    expect(primaryRow()).toBe('심사 웹 · two.example.test')
    await openMerchantTab('MID·개통')
    expect(screen.getByRole('region', { name: `개통 ${MID2}` })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: `개통 ${MID1}` })).toBeNull()
    await openMerchantTab('한도')
    expect(limitTexts()).toEqual([expect.stringContaining('가맹점 공통'), expect.stringContaining(`MID ${MID2}`)])
    for (const name of ['변경 이력', '기술 정보']) expect(panelOf(name)).toHaveTextContent('가맹점 공통')
    // 선택 MID 배지: 상태·기본 여부·발급일·근거 Revision.
    const head = screen.getByRole('region', { name: '선택 MID 정보' })
    expect(head).toHaveTextContent('근거 Revision 2')
    expect(head).not.toHaveTextContent('기본 MID')
  })

  it('MID 0개: "발급된 MID 없음" + 머리의 "첫 MID 발급 요청"(선택 상자 없음); MID 1개: 고정 표시(선택 상자 없음)', async () => {
    const none = detail(MER1, { accounts: [], limits: [] })
    const { show } = setup({ [MER1]: none })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    const head = screen.getByRole('region', { name: '선택 MID 정보' })
    expect(head).toHaveTextContent('발급된 MID 없음')
    expect(screen.queryByLabelText('MID 선택')).toBeNull()
    expect(screen.getByRole('button', { name: '첫 MID 발급 요청' })).toBeInTheDocument()
    document.body.innerHTML = ''

    const one = setup({ [MER1]: detail(MER1, { accounts: [account(MID1, SITE1)] }) })
    one.show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    expect(screen.queryByLabelText('MID 선택')).toBeNull()
    expect(screen.getByRole('region', { name: '선택 MID 정보' })).toHaveTextContent(MID1)
    expect(screen.getByRole('region', { name: '선택 MID 정보' })).toHaveTextContent('기본 MID')
  })

  it('고른 탭·MID는 화면 메모리에 남고(다시 그려도 그대로, URL 불변) 다른 가맹점을 열면 개요·기본 MID로 돌아간다', async () => {
    const { show } = setup({ [MER1]: detail(MER1), [MER2]: detail(MER2) })
    const first = show()
    const url = window.location.href
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    await openMerchantTab('한도')
    first.unmount()
    show()
    await screen.findByRole('tablist', { name: '가맹점 상세 보기' })
    expect(screen.getByRole('tab', { name: '한도' })).toHaveAttribute('aria-selected', 'true')
    expect((screen.getByLabelText('MID 선택') as HTMLSelectElement).value).toBe(MID2)
    expect(window.location.href).toBe(url)
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    await openRow(MER2)
    expect(screen.getByRole('tab', { name: '개요' })).toHaveAttribute('aria-selected', 'true')
    expect((screen.getByLabelText('MID 선택') as HTMLSelectElement).value).toBe(MID1)
  })

  it('결과 불명 잠금: 고르지 않은 MID·다른 탭에 잠금이 있으면 머리 "결과 확인 필요"와 탭 표시; 탭·MID를 바꿔도 잠긴 폼이 남아 같은 submissionId로만 다시 보낸다', async () => {
    const registerPaymentMethodActivation = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: id('act', 9), replayed: true })
    const { show, revisionMemory } = setup(undefined, { registerPaymentMethodActivation })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    await openMerchantTab('MID·개통')
    const area2 = screen.getByRole('region', { name: `개통 ${MID2}` })
    await userEvent.click(within(area2).getByRole('button', { name: '개통 등록(카드)' }))
    await within(area2).findByRole('button', { name: '같은 개통 등록 다시 보내기' })
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)
    // 다른 탭으로 — 머리 알림과 탭 표시.
    await openMerchantTab('개요')
    const alert = screen.getByRole('status', { name: '결과 확인 필요' })
    expect(within(alert).getByRole('button', { name: 'MID·개통 탭' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'MID·개통' })).toHaveAccessibleDescription('· 결과 확인 필요')
    // 다른 MID를 고르면 머리가 잠긴 MID가 따로 있음을 알린다.
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID1)
    expect(screen.getByRole('region', { name: '선택 MID 정보' })).toHaveTextContent('다른 MID에 결과 확인 필요')
    expect(within(screen.getByLabelText('MID 선택')).getByRole('option', { name: `${MID2} — 결과 확인 필요` })).toBeInTheDocument()
    // 알림의 탭 버튼 → MID·개통 탭, MID2로 돌아가면 같은 잠긴 폼(언마운트 없음).
    await userEvent.click(within(screen.getByRole('status', { name: '결과 확인 필요' })).getByRole('button', { name: 'MID·개통 탭' }))
    expect(screen.getByRole('tab', { name: 'MID·개통' })).toHaveAttribute('aria-selected', 'true')
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    const again = screen.getByRole('region', { name: `개통 ${MID2}` })
    expect(within(again).queryByRole('button', { name: '개통 등록(카드)' })).toBeNull()
    await userEvent.click(within(again).getByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    const one = '1'.padStart(32, 'b')
    expect(registerPaymentMethodActivation.mock.calls.map((c) => c[0])).toEqual(Array(2).fill({ submissionId: one, merchantAccountId: MID2, paymentMethod: 'CARD' }))
    expect(hasUnresolvedRequest(revisionMemory)).toBe(false)
  })

  it('결제 준비 상태 줄 = 서비스 개통 현황·개통 점검표(고른 MID) 값 그대로(참고), 글자로 상태를 말한다', async () => {
    const d = detail(MER1, { pendingApprovals: [] })
    const { show } = setup({ [MER1]: d })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    const STATE: Record<string, string> = { DONE: '완료', IN_PROGRESS: '진행 중', WAITING: '승인 대기', NOT_STARTED: '미시작', LATER: '준비 전' }
    const expected = (mid: string) => {
      const steps = serviceOpeningTimeline(d, NOW())
      const step = (key: string) => { const s = steps.find((x) => x.key === key)!; return `${s.label}: ${STATE[s.state]}` }
      const checklist = activationChecklist(d, d.accounts.find((a) => a.merchantAccountId === mid)!, NOW())
      const creation = checklist.filter((c) => c.group === '결제 생성 조건').every((c) => c.state === 'MET') ? '충족' : '미충족'
      const route = { MET: '충족', NOT_MET: '미충족', LATER: '준비 전' }[checklist.find((c) => c.key === 'route')!.state]
      return [step('approval'), step('mid'), step('opening'), `결제 생성 조건: ${creation}`, `카드 Route: ${route}`, step('terms')]
    }
    const line = () => within(screen.getByRole('region', { name: '결제 준비(참고)' })).getAllByRole('listitem').map((li) => li.getAttribute('aria-label'))
    expect(line()).toEqual(expected(MID1))
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    expect(line()).toEqual(expected(MID2))
    for (const li of within(screen.getByRole('region', { name: '결제 준비(참고)' })).getAllByRole('listitem')) {
      // 글자 상태가 보인다(모양 기호는 보조 — aria-hidden).
      expect(li.querySelector('[aria-hidden="true"]')).not.toBeNull()
      expect(li.textContent).toContain(li.getAttribute('aria-label')!.split(': ')[1]!)
    }
  })

  it('모든 탭을 열어 봐도 내부 키(mer_·biz_·site_·ctr_·csa_·act_·apr_·sac_·cra_·tlp_·pas_·cur_·asg_)와 style 속성이 없다', async () => {
    const { show } = setup()
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    for (const name of TAB_NAMES) {
      await openMerchantTab(name as Parameters<typeof openMerchantTab>[0])
      for (const prefix of PREFIXES) expect(domOccurrences(document.body, prefix), `${name} ${prefix}`).toEqual([])
      expect(document.querySelectorAll('[style]')).toHaveLength(0)
    }
  })
})

describe('MERCHANT-IA-001 B4 리뷰 반영', () => {
  const underReview = (mid: string) => ({ activationId: id('act', mid === MID1 ? 1 : 2), paymentMethod: 'CARD', status: 'UNDER_REVIEW', registeredAt: '2026-11-11T01:00:00.000Z', registeredBy: 'admin:adm_SR' })

  it('MINOR-1: 잠금은 남았는데 다시 보낼 폼이 없으면(다시 읽은 상세에 개통이 생김) "상세를 다시 읽어 확인" 안내와 버튼, 탭 알림도 구분 — 잠금은 풀지 않는다', async () => {
    const registerPaymentMethodActivation = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
    const withActivation = detail(MER1, { accounts: [account(MID1, SITE1, { paymentMethods: [underReview(MID1)] }), account(MID2, SITE2)] })
    const merchantDetail = vi.fn().mockResolvedValueOnce(detail()).mockResolvedValue(withActivation)
    const { show, revisionMemory } = setup(undefined, { registerPaymentMethodActivation, merchantDetail })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await openMerchantTab('MID·개통')
    await userEvent.click(within(screen.getByRole('region', { name: `개통 ${MID1}` })).getByRole('button', { name: '개통 등록(카드)' }))
    await within(screen.getByRole('region', { name: `개통 ${MID1}` })).findByRole('button', { name: '같은 개통 등록 다시 보내기' })
    // 목록으로 → 다시 열면 상세에 카드 개통(심사 중)이 보여 등록 버튼(과 다시 보내기)이 없다.
    await userEvent.click(screen.getByRole('button', { name: '목록으로' }))
    await openRow(MER1)
    const banner = screen.getByRole('status', { name: '결과 확인 필요' })
    expect(banner).toHaveTextContent('상세를 다시 읽어 결과를 확인하세요')
    expect(banner).not.toHaveTextContent('같은 요청으로 다시 보내 결과를 확인하세요')
    expect(within(banner).getByRole('button', { name: '상세 다시 읽기' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'MID·개통' })).toHaveAccessibleDescription('· 결과 확인 필요(다시 읽기)')
    await openMerchantTab('MID·개통')
    expect(within(screen.getByRole('region', { name: `개통 ${MID1}` })).queryByRole('button', { name: '같은 개통 등록 다시 보내기' })).toBeNull()
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)
    await userEvent.click(within(screen.getByRole('status', { name: '결과 확인 필요' })).getByRole('button', { name: '상세 다시 읽기' }))
    expect(merchantDetail).toHaveBeenCalledTimes(3)
    expect(hasUnresolvedRequest(revisionMemory)).toBe(true)
    expect(registerPaymentMethodActivation).toHaveBeenCalledTimes(1)
  })

  it('MINOR-3: 머리 "○○ 탭" 버튼으로 바꾸면 초점이 새로 고른 탭으로 간다', async () => {
    const registerPaymentMethodActivation = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
    const { show } = setup(undefined, { registerPaymentMethodActivation })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await openMerchantTab('MID·개통')
    await userEvent.click(within(screen.getByRole('region', { name: `개통 ${MID1}` })).getByRole('button', { name: '개통 등록(카드)' }))
    await screen.findByRole('status', { name: '결과 확인 필요' })
    await openMerchantTab('개요')
    await userEvent.click(within(screen.getByRole('status', { name: '결과 확인 필요' })).getByRole('button', { name: 'MID·개통 탭' }))
    expect(screen.getByRole('tab', { name: 'MID·개통' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tab', { name: 'MID·개통' })).toHaveFocus()
  })

  it('MINOR-4: Site 심사 거절 결과 불명 → 머리 "Site·계약 탭" 버튼과 Site·계약 탭 알림', async () => {
    const rejectSiteReview = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
    const { show } = setup(undefined, { rejectSiteReview })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await openMerchantTab('Site·계약')
    const row = within(screen.getByRole('table', { name: 'Site 심사' })).getByRole('row', { name: '심사 웹 · two.example.test' })
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.click(within(within(row).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    await within(row).findByRole('button', { name: '같은 거절 다시 보내기' })
    await openMerchantTab('개요')
    const banner = screen.getByRole('status', { name: '결과 확인 필요' })
    expect(within(banner).getByRole('button', { name: 'Site·계약 탭' })).toBeInTheDocument()
    expect(within(banner).queryByRole('button', { name: 'MID·개통 탭' })).toBeNull()
    expect(screen.getByRole('tab', { name: 'Site·계약' })).toHaveAccessibleDescription('· 결과 확인 필요')
  })

  it('MINOR-4: 새 Revision 등록 결과 불명 → 머리 "Site·계약 탭" 버튼과 Site·계약 탭 알림', async () => {
    const registerContractRevision = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [listItem(MER1, '데모 가맹점')], nextCursor: null })),
      merchantDetail: vi.fn(async () => detail()), merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })), registerContractRevision,
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRegisterRevision now={NOW} newSubmissionId={() => 'c'.repeat(32)} todayKst={() => '2026-11-11'} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await openMerchantTab('Site·계약')
    await userEvent.click(screen.getByRole('button', { name: '새 Revision 등록' }))
    const form = screen.getByRole('region', { name: '새 Revision 등록 — 계약 1' })
    await userEvent.type(within(form).getByLabelText('시작일'), '2026-11-20')
    await userEvent.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    await within(form).findByRole('group', { name: '결과 확인 필요' })
    await openMerchantTab('개요')
    expect(within(screen.getByRole('status', { name: '결과 확인 필요' })).getByRole('button', { name: 'Site·계약 탭' })).toBeInTheDocument()
    expect(screen.getByRole('tab', { name: 'Site·계약' })).toHaveAccessibleDescription('· 결과 확인 필요')
  })

  it('MINOR-5: 다른 MID를 골라도 고르지 않은 MID 영역은 DOM에 남고(마운트 유지), 2인 요청 폼에 넣던 값도 돌아오면 그대로다', async () => {
    const d = detail(MER1, { accounts: [account(MID1, SITE1), account(MID2, SITE2, { paymentMethods: [underReview(MID2)] })] })
    const { show } = setup({ [MER1]: d })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    await openMerchantTab('MID·개통')
    const area2 = screen.getByRole('region', { name: `개통 ${MID2}` })
    await userEvent.click(within(area2).getByRole('button', { name: '개통 승인 요청' }))
    await userEvent.type(within(area2).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0009')
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID1)
    expect(screen.queryByRole('region', { name: `개통 ${MID2}` })).toBeNull()
    expect(document.querySelector(`section[aria-label="개통 ${MID2}"]`)).toBe(area2)
    expect(area2.closest('[hidden]')).not.toBeNull()
    await userEvent.selectOptions(screen.getByLabelText('MID 선택'), MID2)
    expect(within(screen.getByRole('region', { name: `개통 ${MID2}` })).getByLabelText('기관(카드사/VAN) 회신 참조(필수)')).toHaveValue('VAN-REPLY/2026-0009')
  })

  it('MINOR-2·NIT-3: MID 범위 2인 요청 결과 배너는 MID를 말하고, 준비 줄은 기준(선택 MID / 어느 MID든)을 밝힌다', async () => {
    const requestMerchantPaymentMethodActivation = vi.fn(async () => ({ approvalId: 'apr_01JC0000000000000000000B49' }))
    const d = detail(MER1, { accounts: [account(MID1, SITE1, { paymentMethods: [underReview(MID1)] }), account(MID2, SITE2)] })
    const { show } = setup({ [MER1]: d }, { requestMerchantPaymentMethodActivation })
    show()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await openRow(MER1)
    expect(screen.getByRole('region', { name: '결제 준비(참고)' })).toHaveTextContent('결제 생성 조건·카드 Route는 선택 MID 기준, 결제수단 개통·Site 심사는 어느 MID든 기준')
    await openMerchantTab('MID·개통')
    const area = screen.getByRole('region', { name: `개통 ${MID1}` })
    await userEvent.click(within(area).getByRole('button', { name: '개통 승인 요청' }))
    await userEvent.type(within(area).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0010')
    await userEvent.click(within(area).getByRole('button', { name: '승인 요청 보내기' }))
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent(`결제수단 개통 승인(MID ${MID1})`)
  })
})
