import { describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { MerchantsScreen } from '../screens/MerchantsScreen'
import type { AdminApi, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 1단계: 실제 관리자 콘솔에 참고 시안(docs/ui-reference/reference — 'pg-ops-workspace')의 골격·class·CSS를 입힌다.
 * 메뉴·기능·권한 표시·내부 키 숨김은 그대로이고, 시안의 샘플 브랜드·샘플 데이터·시안 전용 메뉴는 실제 콘솔에 들어오지 않는다.
 */

const REFERENCE_JS = readFileSync('docs/ui-reference/reference/app.js', 'utf8') // Vitest 작업 디렉터리 = frontend/
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8')
const STYLES_CSS = readFileSync('src/styles.css', 'utf8')
const MAIN_TSX = readFileSync('src/main.tsx', 'utf8')

/** 시안에만 있는 샘플 브랜드·샘플 데이터·시안 전용 메뉴 문구(실제 콘솔에 나오면 안 된다). */
const SAMPLE_ONLY = ['pg workspace', 'PG WORKSPACE', 'PG Workspace', '핑거페이', '프로토타입', '메뉴 개편 지도', '김 운영', '운영 관리자 · 데모', '모노마켓', 'mono000001', '화면 설계용 샘플 데이터']
/** 시안 전용 메뉴 이름(실제 콘솔 메뉴에 없어야 한다 — 2단계부터 운영 홈 본문에 "정산·지급 기능은 아직 연결 전" 안내가 있어 메뉴에서만 본다). */
const SAMPLE_MENUS = ['원천사·기준정보', '정산·지급', '리스크 관리', '통계·리포트', '운영·고객지원', '메뉴 개편 지도']

const MER = 'mer_00000000000000000000000001'
const BIZ = 'biz_00000000000000000000000001'
const item: MerchantListItem = {
  merchantId: MER, merchantCode: 'shop000001', merchantName: '데모 가맹점', businessId: BIZ, businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 4, midCount: 2, createdAt: '2026-10-01T00:00:00.000Z',
}

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

const tagsOf = (parent: Element) => Array.from(parent.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))

describe('ADMIN-UI-WORKSPACE-001: reference shell (aside → shell(header, main, footer)) on the real console', () => {
  it('keeps the reference DOM order and class names: aside(brand, workspace, nav#nav, bottom) then .shell(header, main, footer)', async () => {
    const { view } = signedIn(['OPERATOR'])
    await screen.findByRole('navigation', { name: '메뉴' })
    // 원본처럼 wrapper 없이 두 형제(aside, .shell)
    const root = view.container
    expect(tagsOf(root)).toEqual(['aside', 'div.shell'])
    const aside = root.children[0]!
    expect(tagsOf(aside)).toEqual(['div.brand', 'div.workspace', 'nav', 'div.bottom'])
    expect(aside.querySelector('nav')).toHaveAttribute('id', 'nav')
    const shell = root.children[1]!
    expect(tagsOf(shell)).toEqual(['header', 'main', 'footer'])
    const header = shell.children[0]!
    expect(header.querySelector('#crumb')).not.toBeNull()
    // 기대값 변경(리뷰 NIT — 단축키 표시는 플랫폼별): jsdom은 Mac이 아니라 "Ctrl K". Mac 표시는 아래 시험.
    expect(header.querySelector('button.search kbd')).toHaveTextContent('Ctrl K')
  })

  it('review NIT: the shortcut hint is "⌘ K" on Apple platforms (userAgentData or navigator.platform) and "Ctrl K" elsewhere', async () => {
    const platform = vi.spyOn(navigator, 'platform', 'get').mockReturnValue('MacIntel')
    try {
      signedIn(['OPERATOR'])
      expect(within(await screen.findByRole('button', { name: '메뉴 검색' })).getByText('⌘ K').tagName).toBe('KBD')
    } finally {
      platform.mockRestore()
    }
  })

  it('shows the real brand (PayCore) and no sample brand, sample data or reference-only menu', async () => {
    const { view } = signedIn(['OPERATOR', 'APPROVER', 'SECURITY_ADMIN'])
    await screen.findByRole('navigation', { name: '메뉴' })
    const aside = screen.getByRole('complementary')
    expect(within(aside).getByText('PayCore')).toBeInTheDocument()
    expect(aside.querySelector('.brand')).toHaveTextContent('PayCore')
    const text = view.container.textContent ?? ''
    for (const sample of SAMPLE_ONLY) expect(text).not.toContain(sample)
    const menuText = screen.getByRole('navigation', { name: '메뉴' }).textContent ?? ''
    for (const sample of SAMPLE_MENUS) expect(menuText).not.toContain(sample)
  })

  it('the 1-level menu keeps the console order and sections; labels, active class and reference SVG paths only', async () => {
    signedIn(['OPERATOR', 'APPROVER'])
    const nav = await screen.findByRole('navigation', { name: '메뉴' })
    expect(within(nav).getAllByRole('button').map((b) => b.textContent)).toEqual(['운영 홈', '승인함', '내 요청', '사업자·가맹점', '결제 관리', '매입·대사', '계정·권한'])
    expect(Array.from(nav.querySelectorAll('.label')).map((l) => l.textContent)).toEqual(['내 업무', 'PAYMENT OPERATIONS', 'WORKSPACE'])
    const home = within(nav).getByRole('button', { name: '운영 홈' })
    expect(home).toHaveClass('active')
    expect(home).toHaveAttribute('aria-current', 'page')
    for (const path of Array.from(nav.querySelectorAll('svg path'))) expect(REFERENCE_JS).toContain(`'${path.getAttribute('d')}'`)
    await userEvent.click(within(nav).getByRole('button', { name: '계정·권한' }))
    expect(within(nav).getByRole('button', { name: '계정·권한' })).toHaveClass('active')
    expect(home).not.toHaveClass('active')
  })

  it('the bottom keeps the account name, roles and logout (no sample user)', async () => {
    signedIn(['VIEWER'])
    await screen.findByRole('navigation', { name: '메뉴' })
    const bottom = screen.getByRole('complementary').querySelector('.bottom')!
    expect(bottom.querySelector('b')).toHaveTextContent('관리자')
    expect(bottom.querySelector('small')).toHaveTextContent('역할: 조회자')
    expect(within(bottom as HTMLElement).getByRole('button', { name: '로그아웃' })).toHaveClass('btn')
  })

  it('menu page: .heading(eyebrow, h1, p | .actions) then section.card with .tabs (selected tab = .on)', async () => {
    signedIn(['SECURITY_ADMIN'])
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '계정·권한' }))
    const main = screen.getByRole('main')
    const heading = main.querySelector(':scope > .heading')!
    expect(tagsOf(heading)).toEqual(['div', 'div.actions'])
    expect(tagsOf(heading.children[0]!)).toEqual(['div.eyebrow', 'h1', 'p'])
    expect(heading.querySelector('h1')).toHaveTextContent('계정·권한')
    const card = main.querySelector(':scope > section.card')!
    const tabs = card.querySelector(':scope > .tabs')!
    expect(tabs).toHaveAttribute('role', 'tablist')
    expect(within(tabs as HTMLElement).getByRole('tab', { name: '계정 관리' })).toHaveClass('on')
    expect(within(tabs as HTMLElement).getByRole('tab', { name: '접속·감사 이력' })).not.toHaveClass('on')
    // 리뷰 NIT: tabpanel은 선택 탭 것 하나만 있으므로 aria-controls도 선택 탭에만(없는 id를 가리키지 않는다).
    expect(within(tabs as HTMLElement).getByRole('tab', { name: '계정 관리' })).toHaveAttribute('aria-controls', card.querySelector('[role="tabpanel"]')!.id)
    expect(within(tabs as HTMLElement).getByRole('tab', { name: '접속·감사 이력' })).not.toHaveAttribute('aria-controls')
  })
})

describe('ADMIN-UI-WORKSPACE-001: header search is the reference modal (menu names only — no transaction search)', () => {
  it('the overlay is hidden until the search button (or Ctrl+K) opens it; Escape closes it and returns focus', async () => {
    signedIn(['OPERATOR'])
    const button = await screen.findByRole('button', { name: '메뉴 검색' })
    expect(button).toHaveClass('search')
    expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
    await userEvent.click(button)
    const dialog = screen.getByRole('dialog', { name: '메뉴 검색' })
    expect(dialog).toHaveClass('modal')
    expect(dialog.parentElement).toHaveClass('overlay')
    expect(within(dialog).getByRole('searchbox', { name: '메뉴 검색' })).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
    expect(button).toHaveFocus()
    await userEvent.keyboard('{Control>}k{/Control}')
    expect(screen.getByRole('dialog', { name: '메뉴 검색' })).toBeInTheDocument()
    await userEvent.click(within(screen.getByRole('dialog', { name: '메뉴 검색' })).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
  })

  it('review MINOR-1: while open the background is inert, Tab/Shift+Tab stay inside, Escape closes even with focus elsewhere and focus returns to the button', async () => {
    const { view } = signedIn(['OPERATOR'])
    const button = await screen.findByRole('button', { name: '메뉴 검색' })
    await userEvent.click(button)
    const dialog = screen.getByRole('dialog', { name: '메뉴 검색' })
    // 대화상자는 shell 밖(body 끝)에 있고, 그 밖의 body 자식(앱 container)은 inert.
    expect(view.container.contains(dialog)).toBe(false)
    expect(view.container).toHaveAttribute('inert')
    const close = within(dialog).getByRole('button', { name: '닫기' })
    const input = within(dialog).getByRole('searchbox', { name: '메뉴 검색' })
    expect(input).toHaveFocus()
    await userEvent.tab()
    expect(close).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(input).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(close).toHaveFocus()
    // 초점이 대화상자 밖으로 옮겨진 경우에도 Esc는 닫는다.
    ;(document.activeElement as HTMLElement).blur()
    expect(document.body).toHaveFocus()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
    expect(view.container).not.toHaveAttribute('inert')
    expect(button).toHaveFocus()
  })

  it('review NIT: Ctrl/⌘+K does not open the search while another modal dialog is open', async () => {
    signedIn(['OPERATOR'])
    await screen.findByRole('button', { name: '메뉴 검색' })
    const other = document.createElement('div')
    other.setAttribute('role', 'dialog')
    other.setAttribute('aria-modal', 'true')
    document.body.appendChild(other)
    try {
      await userEvent.keyboard('{Control>}k{/Control}')
      expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
    } finally {
      other.remove()
    }
    await userEvent.keyboard('{Meta>}k{/Meta}')
    expect(screen.getByRole('dialog', { name: '메뉴 검색' })).toBeInTheDocument()
  })

  it('review NIT: Ctrl+K also opens the search when a Korean IME turns the key into "ㅏ" (physical KeyK)', async () => {
    signedIn(['OPERATOR'])
    await screen.findByRole('button', { name: '메뉴 검색' })
    // userEvent는 IME 글자에 물리 키(code KeyK)를 붙일 수 없어 fireEvent를 쓴다 — fireEvent는 기다리지 않으므로 먼저 남은 effect(문서 keydown 등록)를 비운다.
    await act(async () => {})
    fireEvent.keyDown(document, { key: 'ㅏ', code: 'KeyK', ctrlKey: true })
    expect(screen.getByRole('dialog', { name: '메뉴 검색' })).toBeInTheDocument()
  })

  it('results are .result buttons of visible menus; choosing one opens it and closes the modal', async () => {
    signedIn(['VIEWER'])
    await userEvent.click(await screen.findByRole('button', { name: '메뉴 검색' }))
    await userEvent.type(screen.getByRole('searchbox', { name: '메뉴 검색' }), '거래')
    const result = within(screen.getByRole('list', { name: '메뉴 검색 결과' })).getByRole('button', { name: '결제 관리 › 거래 조회' })
    expect(result).toHaveClass('result')
    await userEvent.click(result)
    expect(screen.queryByRole('dialog', { name: '메뉴 검색' })).toBeNull()
    expect(screen.getByRole('tab', { name: '거래 조회' })).toHaveAttribute('aria-selected', 'true')
  })
})

describe('ADMIN-UI-WORKSPACE-001: 사업자·가맹점 › 가맹점 조회 in the reference list layout', () => {
  it('card(tabs → filters → tablewrap → tablebottom); the row is strong name + small code with a .tag status; no mer_/biz_ anywhere', async () => {
    signedIn(['OPERATOR'], { merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: 'next' })) })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
    const card = screen.getByRole('main').querySelector(':scope > section.card')!
    const panel = card.querySelector(':scope > [role="tabpanel"]')!
    expect(panel.querySelector('form.filters')).not.toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    const row = await screen.findByRole('row', { name: 'shop000001' })
    const wrap = panel.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('가맹점 목록')
    expect(wrap.nextElementSibling).toHaveClass('tablebottom')
    expect(within(wrap.nextElementSibling as HTMLElement).getByRole('button', { name: '다음' })).toHaveClass('btn')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['가맹점', '사업자', '사업자등록번호(마스킹)', '상태', 'MID 수', '권리 revision', '등록일', '작업'])
    const first = row.querySelector('td')!
    expect(first.querySelector('strong')).toHaveTextContent('데모 가맹점')
    expect(first.querySelector('small')).toHaveTextContent('shop000001')
    expect(row.querySelector('.tag.green')).toHaveTextContent('활성')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })
})

describe('review MINOR-4: merchant list requests — buttons disabled while busy, a late earlier response never overwrites a newer one', () => {
  it('검색 is disabled (aria-busy) while the request is pending and enabled after', async () => {
    let resolve: (v: { items: MerchantListItem[]; nextCursor: string | null }) => void = () => undefined
    const merchantSearch = vi.fn(() => new Promise<{ items: MerchantListItem[]; nextCursor: string | null }>((r) => { resolve = r }))
    render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber={false} />)
    const button = screen.getByRole('button', { name: '검색' })
    await userEvent.click(button)
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    await userEvent.click(button)
    expect(merchantSearch).toHaveBeenCalledTimes(1)
    resolve({ items: [item], nextCursor: 'next' })
    expect(await screen.findByRole('row', { name: 'shop000001' })).toBeInTheDocument()
    expect(button).toBeEnabled()
    expect(screen.getByRole('button', { name: '다음' })).toBeEnabled()
  })

  it('a stale response arriving after a newer search is dropped', async () => {
    const resolvers: ((v: { items: MerchantListItem[]; nextCursor: string | null }) => void)[] = []
    const merchantSearch = vi.fn(() => new Promise<{ items: MerchantListItem[]; nextCursor: string | null }>((r) => { resolvers.push(r) }))
    const { container } = render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber={false} />)
    const form = container.querySelector('form.filters')!
    // 버튼이 막혀 있어도 다른 경로(폼 제출)로 둘째 요청이 생긴 경우를 흉내 낸다.
    fireEvent.submit(form)
    await userEvent.selectOptions(screen.getByLabelText('상태'), 'TERMINATED')
    fireEvent.submit(form)
    expect(merchantSearch).toHaveBeenCalledTimes(2)
    resolvers[1]!({ items: [{ ...item, merchantCode: 'newer00001', merchantName: '새 결과' }], nextCursor: null })
    expect(await screen.findByRole('row', { name: 'newer00001' })).toBeInTheDocument()
    resolvers[0]!({ items: [item], nextCursor: 'next' })
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('row', { name: 'shop000001' })).toBeNull()
    expect(screen.getByRole('row', { name: 'newer00001' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '검색' })).toBeEnabled()
  })
})

describe('ADMIN-UI-WORKSPACE-001: stylesheet — the whole reference CSS (with the final typography block) wins over Tailwind preflight, under utilities', () => {
  it('declares the cascade layer order before importing tailwindcss (workspace above base, below components/utilities)', () => {
    const order = STYLES_CSS.indexOf('@layer theme, base, workspace, components, utilities;')
    expect(order).toBeGreaterThanOrEqual(0)
    expect(order).toBeLessThan(STYLES_CSS.indexOf('@import "tailwindcss"'))
  })

  it('main.tsx loads styles.css then workspace.css and never the uiref preview', () => {
    expect(MAIN_TSX.indexOf("import './styles.css'")).toBeGreaterThanOrEqual(0)
    expect(MAIN_TSX.indexOf("import './workspace.css'")).toBeGreaterThan(MAIN_TSX.indexOf("import './styles.css'"))
    expect(MAIN_TSX).not.toMatch(/uiref/)
  })

  it('workspace.css is self-contained (no import, remote URL or font file)', () => {
    expect(WORKSPACE_CSS).not.toMatch(/@import/)
    expect(WORKSPACE_CSS).not.toMatch(/url\(/i)
    expect(WORKSPACE_CSS).not.toMatch(/@font-face/i)
    expect(WORKSPACE_CSS).not.toMatch(/https?:\/\//i)
  })

  // ADMIN-DS-B1(§7.2): 라이트 대비 보정값 원문 대조(:294)와 시안 선언 블록 순서 대조(:312)는 designTokens.test.ts(토큰 대비 계산 — 보정값 쌍 포함,
  // styles.css --brand와 --ds-brand 일치, 리터럴 위치·다크 선택자)와 §7.4 캡처(값 변경 0)로 옮겼다 — ADMIN-DS-001 §11 매핑표.
})
