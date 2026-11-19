import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import type { AdminApi } from './api/adminApi'
import { apiError, fakeApi } from './test/fakeApi'

const sideMenu = () => within(screen.getByRole('navigation', { name: '메뉴' }))
const menuNames = () => sideMenu().getAllByRole('button').map((b) => b.textContent)
const breadcrumb = () => screen.getByRole('navigation', { name: '경로' })
/** 로그인 확인(`me`)이 끝나 메뉴가 그려질 때까지 기다린다. */
const signedInMenu = async () => within(await screen.findByRole('navigation', { name: '메뉴' }))

/** 역할 하나로 로그인한 App(목록 조회는 빈 결과). */
function signedIn(roles: string[], overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '관리자', roles })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
    accounts: vi.fn(async () => ({ accounts: [] })),
    audit: vi.fn(async () => ({ entries: [] })),
    ...overrides,
  })
  return { api, view: render(<App api={api} />) }
}

describe('App (session, role menu, error mapping)', () => {
  it('starts at the login screen when there is no session', async () => {
    const api = fakeApi({ me: vi.fn(async () => { throw apiError(401, 'UNAUTHENTICATED') }) })
    render(<App api={api} />)
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
  })

  it('shows the display name and only the menus the server roles allow (display only — the server decides)', async () => {
    signedIn(['APPROVER'])
    expect(await screen.findByText('관리자')).toBeInTheDocument()
    expect(menuNames()).toEqual(['운영 홈', '승인함', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'])
    expect(sideMenu().queryByRole('button', { name: '내 요청' })).toBeNull()
  })

  it('D-ADMIN-UI-003: the side menu is grouped under 내 업무 / PAYMENT OPERATIONS / WORKSPACE, per role', async () => {
    const expected: Record<string, string[]> = {
      SECURITY_ADMIN: ['운영 홈', '내 요청', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'],
      // 기대값 변경(ACQ-VIEW-001, D-ACQV-001): 조회 4역할에 매입·대사(매입 현황 탭)가 더해졌다.
      OPERATOR: ['운영 홈', '내 요청', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'],
      VIEWER: ['운영 홈', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'],
      UNMASK: ['운영 홈', '계정·권한'],
    }
    for (const [role, menus] of Object.entries(expected)) {
      const { view } = signedIn([role])
      await screen.findByRole('navigation', { name: '메뉴' })
      expect(menuNames()).toEqual(menus)
      const groups = sideMenu().getAllByRole('group').map((g) => g.getAttribute('aria-label'))
      expect(groups).toEqual([
        ...(menus.includes('내 요청') || menus.includes('승인함') ? ['내 업무'] : []),
        ...(menus.includes('결제 관리') ? ['PAYMENT OPERATIONS'] : []),
        'WORKSPACE',
      ])
      view.unmount()
    }
  })

  it('S8: OPERATOR reaches the correction cancel tab and "my requests" (no findings); SECURITY_ADMIN and APPROVER reach the findings tab', async () => {
    const operator = signedIn(['OPERATOR'])
    await userEvent.click((await signedInMenu()).getByRole('button', { name: '결제 관리' }))
    expect(screen.getByRole('tab', { name: '결제 보정 취소' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('결제 ID')).toBeInTheDocument()
    expect(sideMenu().getByRole('button', { name: '내 요청' })).toBeInTheDocument()
    // 기대값 변경(ACQ-VIEW-001, D-ACQV-001): OPERATOR도 매입·대사 메뉴를 보지만 탭은 매입 현황뿐이다(대사 발견 없음).
    await userEvent.click(sideMenu().getByRole('button', { name: '매입·대사' }))
    expect(screen.getByRole('tab', { name: '매입 현황' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('tab', { name: '대사 발견' })).toBeNull()
    operator.view.unmount()
    for (const role of ['SECURITY_ADMIN', 'APPROVER']) {
      const { view, api } = signedIn([role])
      await userEvent.click((await signedInMenu()).getByRole('button', { name: '매입·대사' }))
      expect(screen.getByRole('tab', { name: '대사 발견' })).toHaveAttribute('aria-selected', 'true')
      expect(await screen.findByRole('heading', { name: '열린 대사 발견 0건' })).toBeInTheDocument()
      expect(api.reconciliation).toHaveBeenCalled()
      expect(sideMenu().queryByRole('button', { name: '결제 관리' })).not.toBeNull()
      view.unmount()
    }
  })

  it('MERCHANT-OPS-001 §5.3: 사업자·가맹점 opens the lookup; the registration number field only for OPERATOR·APPROVER (display only — the server decides)', async () => {
    const expected: [string[], boolean][] = [
      [['OPERATOR'], true], [['APPROVER'], true], [['VIEWER'], false], [['SECURITY_ADMIN'], false], [['VIEWER', 'UNMASK'], false],
    ]
    for (const [roles, brn] of expected) {
      const { view, api } = signedIn(roles)
      await userEvent.click((await signedInMenu()).getByRole('button', { name: '사업자·가맹점' }))
      // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 탭 이름 '가맹점 조회' → '가맹점'(첫 화면은 그대로 가맹점 조회).
      expect(screen.getByRole('tab', { name: '가맹점' })).toHaveAttribute('aria-selected', 'true')
      // MERCHANT-CODE-001 MC-2: 가맹점 조회 검색란은 "가맹점 코드"(내부 키 "가맹점 ID" 검색란 대체).
      expect(screen.getByLabelText('가맹점 코드')).toBeInTheDocument()
      expect(screen.queryByLabelText('사업자등록번호') !== null).toBe(brn)
      // 화면을 연 것만으로는 조회하지 않는다(검색 버튼을 눌러야 — 목록 조회도 서버 속도 제한 대상).
      expect(api.merchantSearch).not.toHaveBeenCalled()
      view.unmount()
    }
  })

  it('계정·권한: tabs switch inside the card, the breadcrumb follows the menu and the active menu is aria-current', async () => {
    const { api } = signedIn(['SECURITY_ADMIN'])
    await signedInMenu()
    expect(within(breadcrumb()).getByText('운영 홈')).toBeInTheDocument()
    await userEvent.click(sideMenu().getByRole('button', { name: '계정·권한' }))
    expect(breadcrumb()).toHaveTextContent('워크스페이스/계정·권한')
    expect(screen.getByRole('heading', { level: 1, name: '계정·권한' })).toBeInTheDocument()
    expect(sideMenu().getByRole('button', { name: '계정·권한' })).toHaveAttribute('aria-current', 'page')
    expect(sideMenu().getByRole('button', { name: '운영 홈' })).not.toHaveAttribute('aria-current')
    const tabs = screen.getByRole('tablist', { name: '계정·권한' })
    expect(within(tabs).getAllByRole('tab').map((t) => t.textContent)).toEqual(['계정 관리', '접속·감사 이력'])
    expect(within(tabs).getByRole('tab', { name: '계정 관리' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('tabpanel', { name: '계정 관리' })).toBeInTheDocument()
    expect(api.accounts).toHaveBeenCalled()
    expect(api.audit).not.toHaveBeenCalled()
    await userEvent.click(within(tabs).getByRole('tab', { name: '접속·감사 이력' }))
    expect(within(tabs).getByRole('tab', { name: '접속·감사 이력' })).toHaveAttribute('aria-selected', 'true')
    expect(within(tabs).getByRole('tab', { name: '계정 관리' })).toHaveAttribute('aria-selected', 'false')
    expect(screen.getByRole('tabpanel', { name: '접속·감사 이력' })).toHaveTextContent('감사 기록')
    expect(api.audit).toHaveBeenCalled()
    // 화살표 키로도 탭을 옮긴다(선택된 탭만 Tab 순서에 있음).
    await userEvent.keyboard('{ArrowLeft}')
    expect(within(tabs).getByRole('tab', { name: '계정 관리' })).toHaveAttribute('aria-selected', 'true')
    expect(within(tabs).getByRole('tab', { name: '계정 관리' })).toHaveFocus()
    expect(within(tabs).getByRole('tab', { name: '접속·감사 이력' })).toHaveAttribute('tabindex', '-1')
  })

  it('UNMASK only: 계정·권한 shows the audit tab alone (no account list call)', async () => {
    const { api } = signedIn(['UNMASK'])
    await userEvent.click((await signedInMenu()).getByRole('button', { name: '계정·권한' }))
    expect(within(screen.getByRole('tablist', { name: '계정·권한' })).getAllByRole('tab').map((t) => t.textContent)).toEqual(['접속·감사 이력'])
    expect(api.accounts).not.toHaveBeenCalled()
  })

  it('운영 홈 is the first screen; a work item opens its screen and marks its menu', async () => {
    signedIn(['APPROVER'], {
      approvals: vi.fn(async () => ({
        approvals: [{
          approvalId: 'apr_1', kind: 'ROLE_GRANT', targetActorId: 'adm_T', targetSnapshot: '{}', grantedRole: 'VIEWER', causeApprovalId: null, requestedBy: 'adm_R',
          requestedByDisplayName: '요청자', requestedAt: '2026-11-01T00:00:00Z', expiresAt: '2026-11-02T00:00:00Z', status: 'PENDING', decidedBy: null,
          decidedByDisplayName: null, decidedAt: null, resultCode: null,
        }],
      })),
    })
    expect((await signedInMenu()).getByRole('button', { name: '운영 홈' })).toHaveAttribute('aria-current', 'page')
    // 구조 단언 변경(ADMIN-UI-WORKSPACE-001 2단계): 지금 처리할 업무는 승인 요청을 한 건씩 보인다(시안 task 행) — 행 이름은 "<종류> 승인 요청".
    await userEvent.click(await screen.findByRole('button', { name: /역할 부여 승인 요청/ }))
    expect(sideMenu().getByRole('button', { name: '승인함' })).toHaveAttribute('aria-current', 'page')
    expect(breadcrumb()).toHaveTextContent('워크스페이스/승인함')
    expect(screen.queryByRole('tablist')).toBeNull()
    expect(await screen.findByRole('row', { name: /ROLE_GRANT adm_T PENDING/ })).toBeInTheDocument()
  })

  it('the top search finds only menus the role can see and jumps there (no transaction search)', async () => {
    signedIn(['OPERATOR'])
    // 구조 단언 변경(ADMIN-UI-WORKSPACE-001): 검색란은 상단 바 검색 버튼이 여는 modal 안에 있다(참고 시안) — 연 뒤의 기대값은 그대로.
    await userEvent.click(await screen.findByRole('button', { name: '메뉴 검색' }))
    const search = screen.getByRole('searchbox', { name: '메뉴 검색' })
    // 기대값 변경(ACQ-VIEW-001): OPERATOR는 이제 매입·대사(매입 현황 탭)를 보므로, 볼 수 없는 메뉴의 예로 '승인함'을 쓴다.
    await userEvent.type(search, '승인함')
    expect(screen.getByRole('status')).toHaveTextContent('찾는 메뉴가 없어요.')
    await userEvent.clear(search)
    await userEvent.type(search, '감사')
    const results = screen.getByRole('list', { name: '메뉴 검색 결과' })
    expect(within(results).getAllByRole('button').map((b) => b.textContent)).toEqual(['계정·권한 › 접속·감사 이력'])
    await userEvent.type(search, '{Enter}')
    expect(screen.getByRole('tab', { name: '접속·감사 이력' })).toHaveAttribute('aria-selected', 'true')
    expect(search).toHaveValue('')
    expect(screen.queryByRole('list', { name: '메뉴 검색 결과' })).toBeNull()
  })

  it('TXN-OPS-001: a VIEWER opens 결제 관리 › 거래 조회 and sees the transaction search (no call until searched)', async () => {
    const { api } = signedIn(['VIEWER'])
    // 구조 단언 변경(ADMIN-UI-WORKSPACE-001 2단계): 운영 홈의 "최근 거래"가 기존 거래 조회를 한 번 부른다(시안 home 구성) — 거래 조회 화면을 연 것만으로는 더 부르지 않는다는 기대는 그대로.
    const menu = await signedInMenu()
    await vi.waitFor(() => expect(api.transactionSearch).toHaveBeenCalledTimes(1))
    vi.mocked(api.transactionSearch).mockClear()
    await userEvent.click(menu.getByRole('button', { name: '결제 관리' }))
    await userEvent.click(screen.getByRole('tab', { name: '거래 조회' }))
    expect(screen.getByRole('tab', { name: '거래 조회' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('heading', { name: '거래 조회' })).toBeInTheDocument()
    expect(screen.getByLabelText('조회 방식')).toBeInTheDocument()
    expect(api.transactionSearch).not.toHaveBeenCalled()
  })

  it('a SESSION_REPLACED answer during work returns to login with the explanation', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '승인자', roles: ['APPROVER'] })),
      approvals: vi.fn(async () => { throw apiError(401, 'SESSION_REPLACED') }),
      reconciliation: vi.fn(async () => ({ openCounts: {}, openTotal: 0, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null })),
    })
    render(<App api={api} />)
    expect(await screen.findByRole('status')).toHaveTextContent('다른 곳에서 로그인')
    expect(screen.getByLabelText('로그인 이름')).toBeInTheDocument()
  })

  it('logout calls the server and returns to login', async () => {
    const api = fakeApi({ me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '감사자', roles: ['VIEWER'] })), audit: vi.fn(async () => ({ entries: [] })) })
    render(<App api={api} />)
    await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
    expect(api.logout).toHaveBeenCalledOnce()
    expect(await screen.findByLabelText('로그인 이름')).toBeInTheDocument()
  })
})
