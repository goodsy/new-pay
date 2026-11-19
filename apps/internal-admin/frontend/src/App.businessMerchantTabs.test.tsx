import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import type { AdminApi, Me, RegistrationOutcome } from './api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from './test/fakeApi'
import { individualRegisteredDetail, listItemOf } from './test/businessFixtures'
import { createScreenMemory } from './lib/screenMemory'
import { openBusinessIn, showBusinessListIn } from './screens/BusinessesScreen'

/**
 * LAYOUT-ROLLOUT B1 (사용자 결정 D-ADMIN-UI-006, Demo 피드백 2026-11-19): 사업자·가맹점 탭 4개 → '가맹점'·'사업자' 2개.
 * 각 목록의 제목 줄(.mapnote / 사업자 조회 머리) 오른쪽 '＋ 가맹점 등록'·'＋ 사업자 등록'(OPERATOR만 — pagesFor 그대로)이 같은 탭 안에서 등록 화면을 열고,
 * 등록 화면 제목 줄의 '← 목록으로'가 목록으로 돌아간다. 등록 화면에서도 부모 탭이 선택(aria-selected)이고 tabpanel은 그 탭에 연결된다.
 * 등록 화면 메모리(결과 불명 잠금·입력)는 탭을 오가도 그대로(App 메모리 — MERCHANT-OPS-002 §5.3 리뷰 m-1과 같은 규칙).
 */

const user = userEvent.setup({ delay: null })
const BRN = '7781234560'

function signedIn(roles: string[], overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async (): Promise<Me> => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
    accounts: vi.fn(async () => ({ accounts: [] })),
    audit: vi.fn(async () => ({ entries: [] })),
    searchBusinesses: vi.fn(async () => ({ items: [], next: null })),
    ...overrides,
  })
  const view = render(<App api={api} />)
  return { api, view }
}
const openMenu = async () => user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
const tab = (name: string) => screen.getByRole('tab', { name })
const panel = () => screen.getByRole('tabpanel')
const typeIn = async (label: string, value: string) => {
  await user.click(screen.getByLabelText(label))
  await user.paste(value)
}

describe('LAYOUT-ROLLOUT B1 (D-ADMIN-UI-006): 사업자·가맹점 tabs 2 + 목록의 등록 버튼', { timeout: 20_000 }, () => {
  it('OPERATOR: two tabs (가맹점 → 사업자); 가맹점 is first; the list title row has "＋ 가맹점 등록" (.btn.primary in .mapnote)', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점', '사업자'])
    expect(tab('가맹점')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('heading', { level: 2, name: '가맹점 조회' })).toBeInTheDocument()
    const add = within(panel()).getByRole('button', { name: '＋ 가맹점 등록' })
    expect(add).toHaveClass('btn', 'primary')
    expect(add.closest('.mapnote')).not.toBeNull()
    expect(screen.queryByRole('button', { name: '목록으로' })).toBeNull()
  })

  it('"＋ 가맹점 등록" opens the wizard inside the same tab — 가맹점 stays selected, the tabpanel is labelled by it; "← 목록으로" returns to the list', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    await user.click(screen.getByRole('button', { name: '＋ 가맹점 등록' }))
    expect(screen.getByRole('list', { name: '등록 단계' })).toBeInTheDocument()
    expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual(['가맹점', '사업자'])
    expect(tab('가맹점')).toHaveAttribute('aria-selected', 'true')
    expect(tab('가맹점')).toHaveAttribute('tabindex', '0')
    expect(tab('사업자')).toHaveAttribute('aria-selected', 'false')
    expect(tab('사업자')).toHaveAttribute('tabindex', '-1')
    expect(panel()).toHaveAttribute('aria-labelledby', 'tab-merchants')
    expect(panel()).toHaveAttribute('id', 'panel-merchants')
    expect(tab('가맹점')).toHaveAttribute('aria-controls', 'panel-merchants')
    expect(screen.getByRole('heading', { level: 1, name: '사업자·가맹점' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '＋ 가맹점 등록' })).toBeNull()
    const back = screen.getByRole('button', { name: '목록으로' })
    expect(back).toHaveClass('btn')
    expect(back.closest('.mapnote')).not.toBeNull()
    await user.click(back)
    expect(screen.queryByRole('list', { name: '등록 단계' })).toBeNull()
    expect(screen.getByRole('heading', { level: 2, name: '가맹점 조회' })).toBeInTheDocument()
    expect(panel()).toHaveAttribute('aria-labelledby', 'tab-merchants')
  })

  it('"＋ 사업자 등록" on the 사업자 list opens the registration form inside 사업자; re-clicking the 사업자 tab opens the list', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    await user.click(tab('사업자'))
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '＋ 가맹점 등록' })).toBeNull()
    const add = screen.getByRole('button', { name: '＋ 사업자 등록' })
    expect(add).toHaveClass('btn', 'primary')
    await user.click(add)
    expect(await screen.findByRole('region', { name: '사업자 기본' })).toBeInTheDocument()
    expect(tab('사업자')).toHaveAttribute('aria-selected', 'true')
    expect(tab('가맹점')).toHaveAttribute('aria-selected', 'false')
    expect(panel()).toHaveAttribute('aria-labelledby', 'tab-businesses')
    expect(screen.getByRole('button', { name: '목록으로' }).closest('.mapnote')).not.toBeNull()
    await user.click(tab('사업자'))
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '＋ 사업자 등록' }))
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
  })

  it('keyboard: on a sub-page the parent tab keeps the roving tabindex and ←/→ move between the two tabs', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    await user.click(screen.getByRole('button', { name: '＋ 가맹점 등록' }))
    tab('가맹점').focus()
    await user.keyboard('{ArrowRight}')
    expect(tab('사업자')).toHaveAttribute('aria-selected', 'true')
    expect(tab('사업자')).toHaveFocus()
    expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
  })

  it('an unknown outcome on 사업자 등록 stays locked across tab switches and "← 목록으로" — the resend reuses the same body', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: 'biz_00000000000000000000000007', replayed: true } satisfies RegistrationOutcome)
    signedIn(['OPERATOR'], {
      createBusiness: send,
      // 다시 보내기 성공 뒤 상세 다시 읽기(C-18)는 이 시험의 관심 밖 — 끝나지 않는 응답으로 둔다.
      businessDetail: vi.fn(() => new Promise<never>(() => undefined)),
    })
    await openMenu()
    await user.click(tab('사업자'))
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await typeIn('사업자등록번호', BRN)
    await typeIn('상호', '데모 사업자')
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    await screen.findByRole('group', { name: '결과 확인 필요' })

    await user.click(tab('가맹점'))
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    await user.click(tab('사업자'))
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    expect(screen.getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '사업자 기본' })).getByLabelText('상호')).toBeDisabled()

    await user.click(screen.getByRole('button', { name: '목록으로' }))
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    expect(screen.getByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '사업자 기본' })).getByLabelText('상호')).toHaveValue('데모 사업자')
    await user.click(screen.getByRole('button', { name: '같은 내용 다시 보내기' }))
    await vi.waitFor(() => expect(send).toHaveBeenCalledTimes(2))
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(storageIsEmpty()).toBe(true)
  })

  it('APPROVER, VIEWER and SECURITY_ADMIN see no registration buttons (pagesFor unchanged — display only, the server decides)', async () => {
    for (const [roles, tabs] of [[['APPROVER'], ['가맹점', '사업자']], [['VIEWER'], ['가맹점']], [['SECURITY_ADMIN'], ['가맹점']]] as const) {
      const { view } = signedIn([...roles])
      await openMenu()
      expect(screen.getAllByRole('tab').map((t) => t.textContent)).toEqual([...tabs])
      expect(screen.queryByRole('button', { name: '＋ 가맹점 등록' })).toBeNull()
      if (tabs.length > 1) {
        await user.click(tab('사업자'))
        expect(await screen.findByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
      }
      expect(screen.queryByRole('button', { name: '＋ 사업자 등록' })).toBeNull()
      expect(screen.queryByRole('button', { name: '목록으로' })).toBeNull()
      view.unmount()
    }
  })
})

/**
 * LAYOUT-ROLLOUT B1 reviewFix1 (독립 리뷰 MINOR-1·MINOR-3):
 * - MINOR-1: 사업자 조회 메모리가 상세 X를 기억한 채 다른 길(운영 홈 '＋ 사업자 등록'·메뉴 검색)로 사업자 등록에 들어와도, '목록으로'와 등록 화면에서의 부모 탭 클릭은 목록을 연다.
 *   바꾸는 것은 사업자 조회 메모리의 'view'뿐 — 결과 확인용 편집 메모리(edit.*·edit.base)는 그대로. 하위 화면이 아닐 때(사업자 탭이 이미 선택) 탭 다시 누르기는 기존 동작(기억한 화면).
 * - MINOR-3: 버튼('＋ … 등록'·'목록으로')으로 화면이 바뀌면 새 화면 제목(h2, tabIndex -1)으로 초점을 옮긴다. 처음 열 때·탭 클릭은 옮기지 않는다.
 */
describe('LAYOUT-ROLLOUT B1 reviewFix1: back to the list and focus on button transitions', { timeout: 20_000 }, () => {
  const REG = individualRegisteredDetail(1)
  const withDetail = () =>
    signedIn(['OPERATOR'], {
      searchBusinesses: vi.fn(async () => ({ items: [listItemOf(REG)], next: null })),
      businessDetail: vi.fn(async () => REG),
      transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
    })
  const sideMenu = () => within(screen.getByRole('navigation', { name: '메뉴' }))
  /** 사업자 탭에서 상세 X를 연 뒤 운영 홈으로 나간다(사업자 조회 메모리는 상세 X를 기억). */
  async function rememberDetailThenGoHome() {
    await openMenu()
    await user.click(tab('사업자'))
    await user.click(await screen.findByRole('button', { name: `${REG.business.businessName} 상세` }))
    expect(await screen.findByRole('region', { name: '사업자 상세' })).toBeInTheDocument()
    await user.click(sideMenu().getByRole('button', { name: '운영 홈' }))
  }

  it('MINOR-1: detail remembered + 운영 홈 "＋ 사업자 등록" → 등록 → "목록으로" opens the list, not the remembered detail', async () => {
    withDetail()
    await rememberDetailThenGoHome()
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    expect(await screen.findByRole('region', { name: '사업자 기본' })).toBeInTheDocument()
    expect(tab('사업자')).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(await screen.findByRole('table', { name: '사업자 목록' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자 상세' })).toBeNull()
    expect(screen.getByRole('button', { name: '＋ 사업자 등록' })).toBeInTheDocument()
  })

  it('MINOR-1: on the 등록 sub-page, clicking the parent 사업자 tab opens the list; when 사업자 is already the screen, re-clicking keeps the remembered detail (unchanged)', async () => {
    withDetail()
    await rememberDetailThenGoHome()
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    await user.click(tab('사업자'))
    expect(await screen.findByRole('table', { name: '사업자 목록' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자 상세' })).toBeNull()

    await user.click(screen.getByRole('button', { name: `${REG.business.businessName} 상세` }))
    expect(await screen.findByRole('region', { name: '사업자 상세' })).toBeInTheDocument()
    await user.click(tab('사업자'))
    expect(screen.getByRole('region', { name: '사업자 상세' })).toBeInTheDocument()
  })

  it('MINOR-1: showBusinessListIn changes only the remembered view — result-check edit memory stays', () => {
    const memory = createScreenMemory()
    openBusinessIn(memory, REG.business.businessId)
    memory.write('edit.base', REG)
    memory.write('edit.cmd.status', { kind: 'unknown' })
    memory.write('edit.form', { any: 'value' })
    showBusinessListIn(memory)
    expect(memory.read('view')).toEqual({ kind: 'list' })
    expect(memory.read('edit.base')).toBe(REG)
    expect(memory.read('edit.cmd.status')).toEqual({ kind: 'unknown' })
    expect(memory.read('edit.form')).toEqual({ any: 'value' })
    expect(memory.read('detail.tab')).toBe('info')
  })

  it('MINOR-3: "＋ 가맹점 등록" / "목록으로" move focus to the new screen title (h2, tabIndex -1); opening the menu and clicking tabs do not', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    const listTitle = () => screen.getByRole('heading', { level: 2, name: '가맹점 조회' })
    expect(listTitle()).not.toHaveFocus()
    expect(document.activeElement?.tagName).not.toBe('H2')
    await user.click(screen.getByRole('button', { name: '＋ 가맹점 등록' }))
    const title = screen.getByRole('heading', { level: 2, name: '가맹점 등록' })
    expect(title).toHaveFocus()
    expect(title).toHaveAttribute('tabindex', '-1')
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(listTitle()).toHaveFocus()
    expect(listTitle()).toHaveAttribute('tabindex', '-1')
    await user.click(tab('사업자'))
    expect(tab('사업자')).toHaveFocus()
  })

  it('MINOR-3: "＋ 사업자 등록" / "목록으로" focus 사업자 등록 / 사업자 조회 titles; the arrow is decorative (name "목록으로")', async () => {
    signedIn(['OPERATOR'])
    await openMenu()
    await user.click(tab('사업자'))
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
    expect(screen.getByRole('heading', { level: 2, name: '사업자 등록' })).toHaveFocus()
    const back = screen.getByRole('button', { name: '목록으로' })
    expect(back).toHaveTextContent('← 목록으로')
    expect(back.querySelector('span[aria-hidden="true"]')).toHaveTextContent('←')
    await user.click(back)
    const listTitle = await screen.findByRole('heading', { level: 2, name: '사업자 조회' })
    expect(listTitle).toHaveFocus()
    expect(listTitle).toHaveAttribute('tabindex', '-1')
  })
})
