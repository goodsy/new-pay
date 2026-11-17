import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { AccountsScreen } from './AccountsScreen'
import type { AccountView, AdminApi } from '../api/adminApi'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 계정·권한 › 계정 관리("모양만"): 대사 발견(96608d3)·승인함과 같이 참고 시안 moduleView(카드 안 탭 → .mapnote → 카드 제목 → 표 → .tablebottom)를
 * 실제 계정 관리 화면에 입힌다. 시안 accessView의 `.rolebar`(이름 b + 입력 + 버튼)는 새 계정 만들기 줄로 쓴다.
 * 기능·API 호출·등록 코드 1회 표시·2인 승인 요청·재활성 원인(서버 후보만)·역할별 표시·오류·세션 종료는 AccountsScreen.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture(업무 의미 없는 고정 식별자): 활성 운영자 1명(OPERATOR), 역할이 정지된 계정 1명(APPROVER 정지), 등록 대기 계정 1명(역할 없음).
 */
const active: AccountView = { actorId: 'adm_A', loginName: 'ops-a', displayName: '운영 A', status: 'ACTIVE', activeRoles: ['OPERATOR'], suspendedRoles: [], pendingPurpose: null }
const suspended: AccountView = { ...active, actorId: 'adm_S', loginName: 'ops-s', displayName: '정지 S', activeRoles: [], suspendedRoles: ['APPROVER'] }
const pending: AccountView = { ...active, actorId: 'adm_P', loginName: 'ops-p', displayName: '대기 P', status: 'PENDING_ENROLLMENT', activeRoles: [], suspendedRoles: [] }
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '보안 관리자', roles: ['SECURITY_ADMIN'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  render(<App api={api} />)
  return api
}

describe('ADMIN-UI-WORKSPACE-001: 계정·권한 › 계정 관리 in the reference list layout', () => {
  it('flush in the menu card: tabpanel > .mapnote(h2) → .cardtop(h3 새 계정 만들기) → .cardbody(form.rolebar) → .tablewrap → .tablebottom', async () => {
    signedIn({ accounts: vi.fn(async () => ({ accounts: [active] })) })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '계정·권한' }))
    const row = await screen.findByRole('row', { name: /ops-a/ })
    // 시안 구조로 옮긴 화면은 탭 패널 바로 안에 그린다(안쪽 여백 칸 없음 — MenuCard flush).
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    expect(shape(panel)).toEqual(['div.mapnote', 'div.cardtop', 'div.cardbody', 'div.tablewrap', 'div.tablebottom'])
    expect(within(panel.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '계정 관리' })).toBeInTheDocument()
    expect(panel.querySelector('.cardtop > h3')).toHaveTextContent('새 계정 만들기')

    // 새 계정 만들기: 시안 .rolebar 줄(입력 2칸 + .btn.primary). 보이는 label 대신 aria-label, 도움말은 aria-describedby로 그대로 연결된다.
    const body = panel.querySelector('.cardbody') as HTMLElement
    const form = within(body).getByRole('form', { name: '새 계정 만들기' })
    expect(form).toHaveClass('rolebar')
    expect(within(form).getByLabelText('새 로그인 이름')).toHaveAccessibleDescription('로그인 이름은 로그인할 때 쓰는 이름입니다.')
    expect(within(form).getByLabelText('표시명')).toHaveAccessibleDescription('표시명은 화면과 감사 기록에 보이는 이름입니다.')
    // reviewFix1 MINOR-2: 보이는 이름(시안 .rolebar `<b>이름</b>` + 입력 aria-labelledby — 매입 현황 레코드 상태 선례). 접근 가능한 이름은 그대로.
    expect(Array.from(form.children).map((c) => c.tagName.toLowerCase())).toEqual(['b', 'input', 'b', 'input', 'button'])
    for (const name of ['새 로그인 이름', '표시명']) {
      const input = within(form).getByLabelText(name)
      expect(document.getElementById(input.getAttribute('aria-labelledby')!)).toBe(input.previousElementSibling)
      expect(input.previousElementSibling).toHaveTextContent(name)
    }
    expect(within(form).getByRole('button', { name: '계정 만들기' })).toHaveClass('btn', 'primary')
    expect(body).toHaveTextContent('만들면 등록 코드가 한 번만 표시됩니다. 역할은 만든 뒤 2인 승인으로 부여합니다.')

    const wrap = panel.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('계정 목록')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['로그인 이름', '표시명', '상태', '활성 역할', '정지 역할', '작업'])
    // 표시명은 시안 표 강조(strong), 상태·역할은 .tag(title = 원래 code — 색 의미는 기존 Badge tone)
    expect(row.querySelector('td > strong')).toHaveTextContent('운영 A')
    expect(row.querySelector('td > .tag.green')).toHaveAttribute('title', 'ACTIVE')
    expect(within(row).getByTitle('OPERATOR')).toHaveClass('tag')

    // 행 작업: native details(CSP — 메뉴 라이브러리 없음)의 summary가 .btn, 펼친 안의 버튼도 .btn(주 동작 .btn.primary, 정지는 파괴 동작 token)
    const summary = row.querySelector('details > summary')!
    expect(summary).toHaveClass('btn')
    expect(summary).toHaveTextContent('작업')
    expect(within(row).getByRole('button', { name: '승인 요청' })).toHaveClass('btn', 'primary')
    expect(within(row).getByRole('button', { name: '정지' })).toHaveClass('btn', 'border-destructive-solid', 'bg-destructive-solid', 'text-white')
    expect(within(row).getByRole('button', { name: 'OPERATOR 회수' })).toHaveClass('btn', 'text-destructive')
    // reviewFix1(Coordinator 1101·1024px 캡처 — 펼친 칸이 표 오른쪽 끝에서 잘림): 요청 칸은 세로로 — 선택 Field 위, 버튼 아래(DOM 순서 그대로).
    const requestBlock = within(row).getByLabelText('승인 요청 종류').closest('details > div > div')!
    expect(requestBlock).toHaveClass('flex-col')
    expect(requestBlock.lastElementChild).toBe(within(row).getByRole('button', { name: '승인 요청' }))
    expect(panel.querySelector('.tablebottom')).toHaveTextContent('표시 1건')
    // 내부 키(actor ID·mer_·biz_)는 화면 어디에도 없다(D-ADMIN-UI-004).
    // reviewFix1 NIT-1: actor ID 접두어 전체(adm_)로 넓힘. 재활성 원인 값(apr_…·episode:…)은 기존처럼 서버 후보로 표시된다(별도 시험).
    expect(domOccurrences(document.body, 'adm_')).toEqual([])
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('read-only (no manage permission): .mapnote → .tablewrap → .tablebottom only; a suspended role is a struck-through .tag', async () => {
    const { container } = render(<AccountsScreen api={fakeApi({ accounts: vi.fn(async () => ({ accounts: [{ ...suspended, loginName: null }] })) })} canManage={false} />)
    const row = await screen.findByRole('row', { name: /정지 S/ })
    expect(shape(container)).toEqual(['div.mapnote', 'div.tablewrap', 'div.tablebottom'])
    expect(Array.from(container.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['표시명', '상태', '활성 역할', '정지 역할'])
    expect(within(row).getByTitle('APPROVER')).toHaveClass('tag', 'line-through')
    expect(row.querySelector('details')).toBeNull()
  })

  it('an error and the 500-row notice live in one .cardnote under the map note; an empty list is one .empty line', async () => {
    const failing = render(<AccountsScreen api={fakeApi({ accounts: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }) })} canManage={false} />)
    expect(await screen.findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(shape(failing.container)).toEqual(['div.mapnote', 'div.cardnote', 'div.tablewrap', 'div.tablebottom'])
    expect(failing.container.querySelector('.tablewrap > .empty')).toHaveTextContent('표시할 계정이 없습니다.')
    expect(failing.container.querySelector('.tablebottom')).toHaveTextContent('표시 0건')
    failing.unmount()

    const { container } = render(<AccountsScreen api={fakeApi({ accounts: vi.fn(async () => ({ accounts: [active], truncated: true })) })} canManage />)
    expect(await screen.findByRole('status')).toHaveTextContent('앞의 500개만')
    expect(screen.getByRole('status').closest('.cardnote')).toBe(container.children[1])
  })

  it('reactivation and re-issue controls are reference buttons (.btn / .btn.primary); behavior is unchanged', async () => {
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts: [suspended, pending] })),
      reactivationCauses: vi.fn(async () => ({ causes: [{ cause: 'apr_01RESET', roles: ['APPROVER'] }] })),
    })
    render(<AccountsScreen api={api} canManage />)
    const row = await screen.findByRole('row', { name: /ops-s/ })
    expect(within(row).getByRole('button', { name: '재활성 원인 조회' })).toHaveClass('btn')
    await userEvent.click(within(row).getByRole('button', { name: '재활성 원인 조회' }))
    expect(await within(row).findByLabelText('재활성 원인')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: '재활성 승인 요청' })).toHaveClass('btn', 'primary')
    const causeBlock = within(row).getByLabelText('재활성 원인').closest('details > div > div')!
    expect(causeBlock).toHaveClass('flex-col')
    expect(causeBlock.lastElementChild).toBe(within(row).getByRole('button', { name: '재활성 승인 요청' }))
    const waiting = screen.getByRole('row', { name: /ops-p/ })
    expect(within(waiting).getByRole('button', { name: '등록 코드 재발급' })).toHaveClass('btn')
    expect(within(waiting).getByTitle('PENDING_ENROLLMENT')).toHaveClass('tag', 'orange')
  })
  it('reviewFix1 MINOR-1: the row-action summary (.btn) gets the same focus ring as buttons — light, contrast-corrected color and dark (no new color)', () => {
    const block = WORKSPACE_CSS.slice(WORKSPACE_CSS.indexOf('PayCore 적응 규칙'), WORKSPACE_CSS.indexOf('라이트 대비 보정'))
    expect(block).toMatch(/^summary:focus-visible\{outline:3px solid #aecfff\}$/m)
    expect(block).toMatch(/^button:focus-visible,a:focus-visible,summary:focus-visible\{outline-color:#1d4ed8\}$/m)
    expect(WORKSPACE_CSS).toMatch(/:root\[data-theme=dark\] summary:focus-visible\{outline-color:#93c5fd\}|:root\[data-theme=dark\] summary:focus-visible[,{]/)
  })
})
