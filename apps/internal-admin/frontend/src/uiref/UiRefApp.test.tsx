import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { UiRefApp } from './UiRefApp'

const LEGACY = [
  { A: '영업관리', B: '110000', C: '1', D: '', E: '', F: '', G: '' },
  { A: '├─ 사업자관리', B: '110100', C: '2', D: '110000', E: '110000', F: '', G: '' },
  { A: '로그인', B: '000001', C: '0', D: '', E: '', F: '', G: '공통 인증으로 분리' },
]

function okFetch(body: unknown = LEGACY) {
  return vi.fn(async () => new Response(JSON.stringify(body), { status: 200, headers: { 'Content-Type': 'application/json' } }))
}

function renderApp(hash = '') {
  window.location.hash = hash
  return render(<UiRefApp legacyUrl="/assets/legacy.json" />)
}

const navButton = (name: string) => within(document.querySelector('nav')!).getByRole('button', { name })

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  vi.stubGlobal('fetch', okFetch())
})

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.location.hash = ''
})

describe('UI 재현(ui-reference) entry', () => {
  it('renders the reference home in the original section order with no runtime styles', () => {
    renderApp()
    expect(screen.getByRole('heading', { level: 1, name: '좋은 하루예요, 김 운영님' })).toBeInTheDocument()
    const main = document.querySelector('main#main')!
    // heading → banner → stats → split → 최근 거래 card → quick (원본 home() 순서)
    expect([...main.children].map((e) => e.className)).toEqual(['heading', 'banner', 'stats', 'split', 'card', 'quick'])
    expect(main.querySelectorAll('.stat')).toHaveLength(4)
    expect(navButton('운영 홈')).toHaveClass('active')
    // 1단 메뉴 순서와 구분 label(app.js drawNav: i=1 앞 PAYMENT OPERATIONS, i=7 앞 WORKSPACE)
    expect([...document.querySelectorAll('nav > *')].map((e) => e.textContent)).toEqual([
      '운영 홈', 'PAYMENT OPERATIONS', '사업자·가맹점', '원천사·기준정보', '결제 관리', '매입·대사', '정산·지급', '리스크 관리3',
      'WORKSPACE', '통계·리포트', '운영·고객지원', '계정·권한',
    ])
    // aside → .shell(header, main, footer) → overlay → toast, CSP: style 요소·style 속성 없음
    expect([...document.querySelector('aside')!.parentElement!.children].map((e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : ''))).toEqual(['aside', 'div', 'div#overlay', 'div#toast'])
    expect(document.querySelectorAll('style, [style]')).toHaveLength(0)
  })

  it('switches menus with the hash in sync and resets tab/query like the original go()', async () => {
    renderApp()
    await userEvent.click(navButton('결제 관리'))
    expect(window.location.hash).toBe('#payment')
    expect(navButton('결제 관리')).toHaveClass('active')
    expect(document.getElementById('crumb')!.textContent).toBe('워크스페이스　/　결제 관리')
    expect(screen.getAllByRole('row')).toHaveLength(5) // header + 4 sample rows
    await userEvent.click(screen.getByRole('tab', { name: '실패·취소요청' }))
    expect(screen.getByRole('tab', { name: '실패·취소요청' })).toHaveClass('on')
    expect(screen.getByText('샘플 1건')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('검색'), '없는거래')
    expect(screen.getByText('조회 결과가 없어요. 검색 조건을 변경해 주세요.')).toBeInTheDocument()
    await userEvent.click(navButton('정산·지급'))
    await userEvent.click(navButton('결제 관리'))
    expect(screen.getByRole('tab', { name: '전체 거래' })).toHaveClass('on')
    expect(screen.getByLabelText('검색')).toHaveValue('')
  })

  it('opens and closes the transaction detail drawer (close button, Escape, backdrop)', async () => {
    renderApp('#payment')
    const overlay = document.getElementById('overlay')!
    expect(overlay).not.toBeVisible()
    await userEvent.click(screen.getByText('T202611020002'))
    expect(overlay).toBeVisible()
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { level: 2, name: '오브젝트 스토어' })).toBeInTheDocument()
    expect(within(dialog).getByText('부분취소 16,000원')).toBeInTheDocument()
    expect(within(dialog).getByRole('button', { name: '닫기' })).toHaveFocus()
    await userEvent.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(overlay).not.toBeVisible()
    await userEvent.click(screen.getByText('T202611020001'))
    await userEvent.keyboard('{Escape}')
    expect(overlay).not.toBeVisible()
    await userEvent.click(screen.getByText('T202611020001'))
    await userEvent.click(overlay)
    expect(overlay).not.toBeVisible()
  })

  it('walks the registration wizard steps and registers only a demo MID row', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp()
    await userEvent.click(screen.getByRole('button', { name: '＋ 사업자 등록' }))
    const step = () => document.querySelector('.wizard [aria-current="step"]')?.textContent
    expect(step()).toBe('1. 사업자 정보')
    await userEvent.type(screen.getByLabelText('사업자명 *'), '테스트상점')
    await userEvent.type(screen.getByLabelText('사업자등록번호 *'), '123-45-67890')
    await userEvent.type(screen.getByLabelText('담당자 *'), '김 운영')
    await userEvent.click(screen.getByRole('button', { name: '다음 단계 →' }))
    expect(step()).toBe('2. 계약·원천사')
    expect(document.querySelectorAll('.wizard .current')).toHaveLength(2)
    await userEvent.selectOptions(screen.getByLabelText('원천사'), '키움페이')
    await userEvent.click(screen.getByRole('button', { name: '다음 단계 →' }))
    expect(step()).toBe('3. 가맹점 설정')
    expect(screen.getByLabelText('서비스명 *')).toHaveValue('테스트상점')
    await userEvent.click(screen.getByRole('button', { name: '이전' }))
    expect(screen.getByLabelText('원천사')).toHaveValue('키움페이')
    await userEvent.click(screen.getByRole('button', { name: '다음 단계 →' }))
    await userEvent.click(screen.getByRole('button', { name: '다음 단계 →' }))
    expect(step()).toBe('4. 검토·등록')
    expect(screen.getByText('2.4% · VAT 별도')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '등록하고 데모 MID 발급' }))
    expect(window.location.hash).toBe('#merchant')
    expect(screen.getByText('demo123456')).toBeInTheDocument()
    expect(document.getElementById('toast')).toHaveTextContent('사업자를 등록하고 데모 MID demo123456를 발급했어요')
  })

  it('loads the menu map from the same-origin legacy.json and shows a failure state when it cannot', async () => {
    const fetchMock = okFetch()
    vi.stubGlobal('fetch', fetchMock)
    const first = renderApp('#map')
    expect(screen.getByText('메뉴 지도 데이터를 불러오는 중이에요')).toBeInTheDocument()
    expect(await screen.findByText('110100')).toBeInTheDocument()
    expect(fetchMock).toHaveBeenCalledWith('/assets/legacy.json')
    expect(document.querySelectorAll('#maprows tbody tr')).toHaveLength(3)
    expect(screen.getByRole('button', { name: '공통 인증 →' })).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('레거시 메뉴 검색'), '1101')
    expect(document.querySelectorAll('#maprows tbody tr')).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: '사업자·가맹점 →' }))
    expect(window.location.hash).toBe('#merchant')
    first.unmount()

    vi.stubGlobal('fetch', vi.fn(async () => new Response('nope', { status: 500 })))
    renderApp('#map')
    await waitFor(() => expect(screen.getByText('메뉴 지도 데이터를 불러오지 못했어요. 새로고침해 주세요.')).toBeInTheDocument())
    expect(document.querySelectorAll('#maprows tbody tr')).toHaveLength(0)
  })

  it('keeps the access screen a demo (toggles stay local, save only shows a toast)', async () => {
    renderApp('#access')
    const box = screen.getByLabelText('정산·지급 등록 수정')
    expect(box).not.toBeChecked()
    await userEvent.selectOptions(screen.getByLabelText('역할 선택'), '정산 담당자')
    expect(screen.getByLabelText('정산·지급 등록 수정')).toBeChecked()
    await userEvent.click(screen.getByLabelText('결제 관리 승인'))
    expect(screen.getByLabelText('결제 관리 승인')).toBeChecked()
    await userEvent.click(screen.getByRole('button', { name: '데모 권한 저장' }))
    expect(document.getElementById('toast')).toHaveTextContent('실제 권한에는 반영되지 않습니다')
    expect(fetch).toHaveBeenCalledTimes(1) // legacy.json만 — 권한 저장은 요청을 보내지 않는다
  })
})

describe('UI 재현 stylesheet', () => {
  it('is the reference style.css byte for byte and does not pull the admin Tailwind stylesheet', () => {
    expect(readFileSync('src/uiref/style.css', 'utf8')).toBe(readFileSync('docs/ui-reference/reference/style.css', 'utf8'))
    const main = readFileSync('src/uiref/main.tsx', 'utf8')
    expect(main).not.toMatch(/import\s+['"][^'"]*styles\.css['"]/)
    expect(readFileSync('src/main.tsx', 'utf8')).not.toMatch(/uiref/)
  })

  it('uses the reference legacy.json byte for byte', () => {
    expect(readFileSync('src/uiref/data/legacy.json', 'utf8')).toBe(readFileSync('docs/ui-reference/reference/legacy.json', 'utf8'))
  })
})

// 사용자 결정(2026-11-15): UI 재현은 PC 로컬 미리보기 전용 — 기본 빌드(Gradle frontendBuild → 관리자 jar static/)에 들어가면 안 된다.
describe('UI 재현 빌드 분리', () => {
  type Built = { build: { outDir: string; rolldownOptions: { input: Record<string, string> } } }
  async function configFor(mode: string): Promise<Built> {
    const { default: config } = await import('../../vite.config')
    const fn = config as unknown as (env: { command: 'build'; mode: string; isSsrBuild: boolean; isPreview: boolean }) => Built
    return fn({ command: 'build', mode, isSsrBuild: false, isPreview: false })
  }

  it('builds only the admin index.html into frontend-dist by default (production mode)', async () => {
    const c = await configFor('production')
    expect(c.build.rolldownOptions.input).toEqual({ index: 'index.html' })
    expect(c.build.outDir).toBe('../build/frontend-dist')
  })

  it('builds the reproduction only in uiref mode, into a separate uiref-dist', async () => {
    const c = await configFor('uiref')
    expect(c.build.rolldownOptions.input).toEqual({ uiref: 'ui-reference.html' })
    expect(c.build.outDir).toBe('../build/uiref-dist')
  })

  it('keeps Gradle frontendBuild on the default mode and copies only frontend-dist into static/', () => {
    const gradle = readFileSync('../../../build-logic/src/main/kotlin/paycore.frontend.gradle.kts', 'utf8')
    expect(gradle).toContain('runNode("node_modules/vite/bin/vite.js", "build")\n')
    expect(gradle).not.toMatch(/--mode|uiref-dist/)
    expect(gradle).toContain('from(frontendBuild) { into("static") }')
  })
})
