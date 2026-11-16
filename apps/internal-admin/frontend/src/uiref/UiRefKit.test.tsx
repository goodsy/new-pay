import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { WorkspaceProvider } from './store/WorkspaceProvider'

// 기대값 출처: docs/pg-workspace-react-kit/reference/original/app.js(진실 원천)·SCREENS.md. 샘플 데이터만 쓰고 요청을 보내지 않는다.

function renderApp(hash = '') {
  window.history.replaceState(null, '', `${window.location.pathname}${hash}`)
  return render(
    <WorkspaceProvider>
      <App />
    </WorkspaceProvider>,
  )
}

const nav = () => document.querySelector('nav')!
const navButton = (name: string) => within(nav()).getByRole('button', { name })
const overlay = () => document.getElementById('overlay')
const dialogs = () => document.querySelectorAll('[role="dialog"]')
const crumb = () => document.getElementById('crumb')!.textContent
const bodyRows = () => [...document.querySelectorAll('#results tbody tr')]
const resultTexts = () => [...document.querySelectorAll('#globalresults .result')].map((b) => b.textContent)

let fetchSpy: ReturnType<typeof vi.fn>
let user: ReturnType<typeof userEvent.setup>
beforeEach(() => {
  // delay: null — 키 입력마다 timer 를 기다리지 않는다(동작은 같고, 전체 Vitest 부하에서 5초 timeout 에 걸리지 않게)
  user = userEvent.setup({ delay: null })
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  fetchSpy = vi.fn(async () => {
    throw new Error('uiref preview must not send requests')
  })
  vi.stubGlobal('fetch', fetchSpy)
})

afterEach(() => {
  expect(fetchSpy).not.toHaveBeenCalled()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', window.location.pathname)
})

describe('공통 프레임 · 운영 홈 (SCREENS §0·§1)', () => {
  it('renders the original shell: aside → .shell(header, main, footer) → #toast, no overlay while closed, no runtime styles', () => {
    renderApp('#home')
    const root = document.querySelector('aside')!.parentElement!
    expect([...root.children].map((e) => e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (e.className ? '.' + e.className : ''))).toEqual([
      'aside',
      'div.shell',
      'div#toast',
    ])
    expect([...document.querySelector('.shell')!.children].map((e) => e.tagName.toLowerCase())).toEqual(['header', 'main', 'footer'])
    expect(document.querySelector('footer')!.textContent).toBe('PG WORKSPACE　　화면 설계용 샘플 데이터 · 실제 결제 및 송금이 실행되지 않습니다')
    expect(document.querySelector('header .search')!.textContent).toBe('⌕　메뉴, 가맹점, TID 검색　 ⌘ K')
    expect(overlay()).toBeNull()
    expect(document.querySelectorAll('style, [style]')).toHaveLength(0)
  })

  it('lists the 10 menus with the group labels before index 1 and 7, risk badge 3 and the active menu', () => {
    renderApp('#home')
    expect([...nav().children].map((e) => e.textContent)).toEqual([
      '운영 홈', 'PAYMENT OPERATIONS', '사업자·가맹점', '원천사·기준정보', '결제 관리', '매입·대사', '정산·지급', '리스크 관리3',
      'WORKSPACE', '통계·리포트', '운영·고객지원', '계정·권한',
    ])
    expect(navButton('운영 홈')).toHaveClass('active')
    expect(navButton('운영 홈')).toHaveAttribute('aria-current', 'page')
    expect(document.querySelector('aside a.brand')).toHaveAttribute('href', '#home')
    expect(crumb()).toBe('워크스페이스　/　운영 홈')
  })

  it('renders the home sections in the original order with 4 stats, 4 tasks, the 4-step pipeline and recent payments', () => {
    renderApp('#home')
    expect(screen.getByRole('heading', { level: 1, name: '좋은 하루예요, 김 운영님' })).toBeInTheDocument()
    const main = document.querySelector('main')!
    expect([...main.children].map((e) => e.className)).toEqual(['heading', 'banner', 'stats', 'split', 'card', 'quick'])
    expect(main.querySelectorAll('.stat')).toHaveLength(4)
    expect(main.querySelector('.stat strong')!.textContent).toBe('128,400,000원')
    expect(main.querySelectorAll('.task')).toHaveLength(4)
    expect([...main.querySelectorAll('.task .tag')].map((t) => t.className)).toEqual(['tag blue', 'tag orange', 'tag orange', 'tag orange'])
    expect([...main.querySelectorAll('.pipe .tag')].map((t) => `${t.textContent}:${t.className}`)).toEqual([
      '완료:tag green', '완료:tag green', '진행 중:tag blue', '대기:tag blue',
    ])
    expect(main.querySelectorAll('.card tbody tr.clickable')).toHaveLength(4)
    expect(screen.getByRole('button', { name: '2026. 11. 02　⌄' })).toHaveClass('btn')
  })

  it('home links navigate like go(): task row, quick link, banner and date button', async () => {
    renderApp('#home')
    await user.click(screen.getByText('원천사 입금 차이를 확인해 주세요'))
    expect(window.location.hash).toBe('#acquire')
    await user.click(navButton('운영 홈'))
    await user.click(screen.getByText('계정·권한 관리'))
    expect(window.location.hash).toBe('#access')
    await user.click(navButton('운영 홈'))
    await user.click(screen.getByText('정산 확인하기　→', { normalizer: (t) => t }))
    expect(window.location.hash).toBe('#settle')
    await user.click(navButton('운영 홈'))
    await user.click(screen.getByText('2026. 11. 02　⌄', { normalizer: (t) => t }))
    expect(window.location.hash).toBe('#report')
    expect(window.scrollTo).toHaveBeenCalledWith(0, 0)
  })
})

describe('사내 해시 라우터 (원본 #hash URL)', () => {
  it.each(['', '#', '#nope', '#HOME'])('replaces an invalid hash %j with #home without adding history', (hash) => {
    window.history.replaceState(null, '', `${window.location.pathname}${hash}`)
    const before = window.history.length
    render(
      <WorkspaceProvider>
        <App />
      </WorkspaceProvider>,
    )
    expect(window.location.hash).toBe('#home')
    expect(window.history.length).toBe(before)
    expect(crumb()).toBe('워크스페이스　/　운영 홈')
  })

  it('opens every page directly from its hash', () => {
    const titles: Record<string, string> = {
      home: '운영 홈', merchant: '사업자·가맹점', source: '원천사·기준정보', payment: '결제 관리', acquire: '매입·대사', settle: '정산·지급',
      risk: '리스크 관리', report: '통계·리포트', ops: '운영·고객지원', access: '계정·권한', map: '메뉴 개편 지도',
    }
    for (const [id, title] of Object.entries(titles)) {
      const view = renderApp('#' + id)
      expect(window.location.hash).toBe('#' + id)
      expect(crumb()).toBe('워크스페이스　/　' + title)
      view.unmount()
    }
  })

  it('follows browser hash changes (back/forward) to valid pages and resets an invalid one to #home', async () => {
    renderApp('#home')
    window.location.hash = 'risk'
    await waitFor(() => expect(crumb()).toBe('워크스페이스　/　리스크 관리'))
    expect(navButton('리스크 관리3')).toHaveClass('active')
    window.location.hash = 'unknown'
    await waitFor(() => expect(window.location.hash).toBe('#home'))
    await waitFor(() => expect(crumb()).toBe('워크스페이스　/　운영 홈'))
  })

  it('go() resets tab, query and status even when navigating to the same module (ModulePage remount)', async () => {
    renderApp('#payment')
    await user.click(screen.getByRole('tab', { name: '실패·취소요청' }))
    await user.type(screen.getByLabelText('검색'), '오브젝트')
    expect(bodyRows()).toHaveLength(1)
    await user.click(navButton('결제 관리'))
    expect(screen.getByRole('tab', { name: '전체 거래' })).toHaveClass('on')
    expect(screen.getByRole('tab', { name: '전체 거래' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByLabelText('검색')).toHaveValue('')
    expect(bodyRows()).toHaveLength(4)
  })
})

describe('업무 모듈 공통 템플릿 (SCREENS §2)', () => {
  it('filters by tab, case-insensitive full-row search and status, and resets on tab change and 초기화', async () => {
    renderApp('#payment')
    expect(screen.getByLabelText('검색')).toHaveAttribute('placeholder', '가맹점명, TID 또는 주문번호 검색')
    expect([...screen.getByLabelText('상태').querySelectorAll('option')].map((o) => o.textContent)).toEqual(['전체 상태', '승인 완료', '부분 취소', '입금 대기'])
    await user.type(screen.getByLabelText('검색'), 'credit')
    expect(screen.getByText('조회 결과가 없어요. 검색 조건을 변경해 주세요.')).toBeInTheDocument()
    expect(screen.getByText('샘플 0건')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(bodyRows()).toHaveLength(4)
    await user.selectOptions(screen.getByLabelText('상태'), '승인 완료')
    expect(bodyRows()).toHaveLength(2)
    await user.click(screen.getByRole('tab', { name: '가상계좌·환불' }))
    expect(screen.getByLabelText('상태')).toHaveValue('전체 상태')
    expect(bodyRows().map((r) => r.querySelector('small')!.textContent)).toEqual(['T202611020003'])
    await user.click(screen.getByRole('tab', { name: '정기결제' }))
    expect(bodyRows()).toHaveLength(0)
    // 필터가 정의된 탭에는 안내문이 없다
    expect(document.querySelector('.card .mapnote')).toBeNull()
    expect(document.querySelector('.tablebottom')!.textContent).toBe('샘플 0건1　 /　 1')
  })

  it('shows the .mapnote only for tabs after index 1 without a row filter', async () => {
    renderApp('#merchant')
    expect(screen.getByLabelText('검색')).toHaveAttribute('placeholder', '이름 또는 관리번호 검색')
    await user.click(screen.getByRole('tab', { name: '계약·온보딩' }))
    expect(document.querySelector('.card .mapnote')).toBeNull()
    expect(bodyRows()).toHaveLength(2)
    await user.click(screen.getByRole('tab', { name: 'GID / VID' }))
    expect(document.querySelector('.card .mapnote')!.textContent).toBe(
      'GID / VID · 관련 정보를 선택한 대상의 상세 패널에서 확인하세요. 아래는 연결된 대상 목록입니다.',
    )
    expect(bodyRows()).toHaveLength(4)
  })

  it('renders cells as [strong+small] | col | col | status tag | .amount | ›, with status tones from lib/status', () => {
    renderApp('#acquire')
    const cells = [...bodyRows()[1]!.querySelectorAll('td')]
    expect(cells.map((c) => c.textContent)).toEqual(['키움페이ACQ-20261102-02', '2026.11.01', '824건', '차이 발생', '128,000원', '›'])
    expect(cells[3]!.querySelector('.tag')).toHaveClass('tag', 'orange')
    expect(cells[4]).toHaveClass('amount')
    expect(document.querySelectorAll('thead th')[4]).toHaveClass('amount')
    expect(bodyRows()[0]!.querySelector('.tag')).toHaveClass('green')
  })

  it('settle shows the payout-order banner whose action opens the ST-20261102-001 drawer', async () => {
    renderApp('#settle')
    expect(document.querySelector('main > .banner b')!.textContent).toBe('지급은 검증 → 승인 → 송금 순서로 진행해요')
    await user.click(screen.getByRole('button', { name: '승인 대기 확인 →' }))
    expect(within(screen.getByRole('dialog')).getByText('ST-20261102-001')).toBeInTheDocument()
  })

  it.each([
    ['acquire', '대사 실행 요청', 'ACQ-20261102-01'],
    ['risk', '검토 이력 작성', 'RM-20261102-01'],
  ])('%s primary action opens the first row detail', async (hash, action, key) => {
    renderApp('#' + hash)
    await user.click(screen.getByRole('button', { name: action }))
    expect(within(screen.getByRole('dialog')).getByText(key)).toBeInTheDocument()
  })
})

describe('상세 Drawer 열기/닫기 (행 클릭·Enter → Drawer, ESC·배경 → 닫힘)', () => {
  it('opens on row click with the common header and focuses the close button; × closes', async () => {
    renderApp('#payment')
    await user.click(screen.getByText('T202611020002'))
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveClass('drawer')
    expect(overlay()).toHaveClass('overlay')
    expect(within(dialog).getByText('결제 관리 / 상세')).toHaveClass('eyebrow')
    expect(within(dialog).getByRole('heading', { level: 2, name: '오브젝트 스토어' })).toBeInTheDocument()
    expect(dialog.querySelector('p.subtle')!.textContent).toBe('T202611020002　부분 취소')
    expect([...dialog.querySelectorAll('.detailgrid label')].map((l) => l.textContent)).toEqual(['결제수단', '거래일시', '상태', '금액'])
    expect(within(dialog).getByText('부분취소 16,000원')).toBeInTheDocument()
    expect(dialog.lastElementChild).toHaveTextContent('화면 시연용 데이터 · 실제 시스템에 반영되지 않습니다.')
    expect(dialog.lastElementChild).toHaveClass('subtle')
    expect(within(dialog).getByRole('button', { name: '닫기' })).toHaveFocus()
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
    expect(overlay()).toBeNull()
  })

  it('opens with Enter on a focused row and closes with Escape', async () => {
    renderApp('#settle')
    const row = bodyRows()[2] as HTMLElement
    expect(row).toHaveAttribute('tabindex', '0')
    act(() => row.focus())
    expect(row).toHaveFocus()
    await user.keyboard('{Enter}')
    expect(within(screen.getByRole('dialog')).getByRole('heading', { level: 2, name: '그린리빙' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    expect(overlay()).toBeNull()
  })

  it('closes on a backdrop click but not on a click inside the drawer', async () => {
    renderApp('#merchant')
    await user.click(screen.getByText('obj000002'))
    await user.click(within(screen.getByRole('dialog')).getByText('서비스 개통 현황'))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(overlay()!)
    expect(overlay()).toBeNull()
  })

  it('detail buttons navigate with go() and close the drawer', async () => {
    renderApp('#merchant')
    await user.click(screen.getByText('mono000001'))
    expect(within(screen.getByRole('dialog')).getByText('mono000001 · GID: G-DEMO01 · VID: V-DEMO01')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '정산 보기' }))
    expect(window.location.hash).toBe('#settle')
    expect(overlay()).toBeNull()
    await user.click(screen.getByText('ST-20261102-002'))
    expect(within(screen.getByRole('dialog')).getByRole('heading', { level: 3, name: '정산 처리 단계' })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await user.click(navButton('원천사·기준정보'))
    await user.click(screen.getByText('SRC-002'))
    await user.click(screen.getByRole('button', { name: '연결 가맹점 보기 →' }))
    expect(window.location.hash).toBe('#merchant')
  })
})

describe('전역 검색 (⌘K / Ctrl+K, SCREENS §6)', () => {
  it.each([
    ['Ctrl+K', '{Control>}k{/Control}'],
    ['⌘K', '{Meta>}k{/Meta}'],
  ])('%s opens the search modal with #globalq focused and all 10 menus listed', async (_name, keys) => {
    renderApp('#home')
    await user.keyboard(keys)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveClass('modal')
    const input = document.getElementById('globalq')!
    expect(input).toHaveFocus()
    expect(input).toHaveAttribute('placeholder', '메뉴, 가맹점명 또는 TID 검색')
    expect(resultTexts()).toEqual([
      '운영 홈 메뉴 →', '사업자·가맹점 메뉴 →', '원천사·기준정보 메뉴 →', '결제 관리 메뉴 →', '매입·대사 메뉴 →',
      '정산·지급 메뉴 →', '리스크 관리 메뉴 →', '통계·리포트 메뉴 →', '운영·고객지원 메뉴 →', '계정·권한 메뉴 →',
    ])
    await user.keyboard('{Escape}')
    expect(overlay()).toBeNull()
  })

  it('opens from the header search button; menu hits first, then row hits; a row hit replaces the modal with the drawer', async () => {
    renderApp('#home')
    await user.click(document.querySelector<HTMLButtonElement>('header .search')!)
    await user.type(document.getElementById('globalq')!, '정산')
    expect(resultTexts()).toEqual([
      '정산·지급 메뉴 →',
      '모노마켓 RM-20261102-02 · 리스크 관리',
      '일 정산 생성 JOB-20261102-01 · 운영·고객지원',
      '원천사 대사 JOB-20261102-03 · 운영·고객지원',
    ])
    await user.click(screen.getByRole('button', { name: '모노마켓 RM-20261102-02 · 리스크 관리' }))
    expect(dialogs()).toHaveLength(1)
    const dialog = screen.getByRole('dialog')
    expect(dialog).toHaveClass('drawer')
    expect(within(dialog).getByText('리스크 관리 / 상세')).toBeInTheDocument()
  })

  it('matches rows case-insensitively, caps results at 20 and shows the empty state', async () => {
    renderApp('#home')
    await user.keyboard('{Control>}k{/Control}')
    const input = document.getElementById('globalq')!
    await user.type(input, 'nice')
    expect(resultTexts()).toEqual([
      '모노마켓 mono000001 · 사업자·가맹점',
      '데일리 커피 daily00003 · 사업자·가맹점',
      'NICE SRC-001 · 원천사·기준정보',
      'NICE ACQ-20261102-01 · 매입·대사',
      'NICE · 영중소 ACQ-20261102-03 · 매입·대사',
    ])
    await user.clear(input)
    await user.type(input, '0')
    const texts = resultTexts()
    expect(texts).toHaveLength(20)
    expect(texts[19]).toBe('모노마켓 RM-20261102-02 · 리스크 관리')
    await user.clear(input)
    await user.type(input, '존재하지않는검색어')
    expect(resultTexts()).toEqual([])
    expect(document.querySelector('#globalresults .empty')).toHaveTextContent('검색 결과가 없어요')
  })

  it('a menu hit navigates and closes the modal; reopening starts with an empty query', async () => {
    renderApp('#home')
    await user.keyboard('{Control>}k{/Control}')
    await user.type(document.getElementById('globalq')!, '정산')
    await user.keyboard('{Control>}k{/Control}')
    expect(document.getElementById('globalq')).toHaveValue('')
    await user.type(document.getElementById('globalq')!, '정산')
    await user.click(screen.getByRole('button', { name: '정산·지급 메뉴 →' }))
    expect(window.location.hash).toBe('#settle')
    expect(overlay()).toBeNull()
  })
})

describe('오버레이는 동시에 하나만 (openDrawer / openModal / closeOverlay)', () => {
  it('Ctrl+K over an open drawer replaces it with the search modal', async () => {
    renderApp('#payment')
    await user.click(screen.getByText('T202611020001'))
    expect(screen.getByRole('dialog')).toHaveClass('drawer')
    await user.keyboard('{Control>}k{/Control}')
    expect(dialogs()).toHaveLength(1)
    expect(document.querySelectorAll('#overlay')).toHaveLength(1)
    expect(screen.getByRole('dialog')).toHaveClass('modal')
  })

  it('opening another detail remounts the drawer body (no carried-over memo input)', async () => {
    renderApp('#acquire')
    await user.click(screen.getByText('ACQ-20261102-01'))
    await user.type(screen.getByLabelText('검토 메모'), '메모')
    await user.keyboard('{Control>}k{/Control}')
    await user.type(document.getElementById('globalq')!, 'ACQ-20261102-02')
    await user.click(screen.getByRole('button', { name: '키움페이 ACQ-20261102-02 · 매입·대사' }))
    expect(dialogs()).toHaveLength(1)
    expect(screen.getByLabelText('검토 메모')).toHaveValue('')
  })
})
