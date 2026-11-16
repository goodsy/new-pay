import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { UiRefApp } from './UiRefApp'

// 기대값은 docs/ui-reference/reference/app.js의 searchAll()·saveSource()·detail()/submitReview()·exportRows()·toast()에서 가져왔다.
// 샘플 데이터(app.js conf)만 쓰고 실제 API 요청을 보내지 않는다.

function renderApp(hash = '') {
  window.location.hash = hash
  return render(<UiRefApp legacyUrl="/assets/legacy.json" />)
}

const overlay = () => document.getElementById('overlay')!
const toastEl = () => document.getElementById('toast')!
const resultTexts = () => [...document.querySelectorAll('#globalresults .result')].map((b) => b.textContent)
const bodyRows = () => [...document.querySelectorAll('#results tbody tr')]
const cellTexts = (tr: Element) => [...tr.querySelectorAll('td')].map((td) => td.textContent)

beforeEach(() => {
  vi.spyOn(window, 'scrollTo').mockImplementation(() => {})
  vi.stubGlobal(
    'fetch',
    vi.fn(async () => new Response('[]', { status: 200, headers: { 'Content-Type': 'application/json' } })),
  )
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.location.hash = ''
})

describe('SearchModal (app.js openSearch/searchAll)', () => {
  async function openSearch() {
    await userEvent.click(document.querySelector<HTMLButtonElement>('header .search')!)
    return screen.getByLabelText('통합 검색')
  }

  it('lists every menu for an empty query and focuses the search input', async () => {
    renderApp()
    const input = await openSearch()
    expect(input).toHaveFocus()
    expect(resultTexts()).toEqual([
      '운영 홈 메뉴 →', '사업자·가맹점 메뉴 →', '원천사·기준정보 메뉴 →', '결제 관리 메뉴 →', '매입·대사 메뉴 →',
      '정산·지급 메뉴 →', '리스크 관리 메뉴 →', '통계·리포트 메뉴 →', '운영·고객지원 메뉴 →', '계정·권한 메뉴 →',
    ])
  })

  it('shows menu-name hits first, then sample-row hits in conf order, and each hit navigates', async () => {
    renderApp()
    await userEvent.type(await openSearch(), '정산')
    expect(resultTexts()).toEqual([
      '정산·지급 메뉴 →',
      '모노마켓 RM-20261102-02 · 리스크 관리',
      '일 정산 생성 JOB-20261102-01 · 운영·고객지원',
      '원천사 대사 JOB-20261102-03 · 운영·고객지원',
    ])
    // 행 hit → 해당 업무 상세 drawer
    await userEvent.click(screen.getByRole('button', { name: '모노마켓 RM-20261102-02 · 리스크 관리' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByText('리스크 관리 / 상세')).toBeInTheDocument()
    expect(within(dialog).getByRole('heading', { level: 2, name: '모노마켓' })).toBeInTheDocument()
    // 다시 열면 검색어가 비워진다(app.js openSearch → searchAll('')). 메뉴 hit → go()
    await userEvent.type(await openSearch(), '정산')
    await userEvent.click(screen.getByRole('button', { name: '정산·지급 메뉴 →' }))
    expect(window.location.hash).toBe('#settle')
    expect(overlay()).not.toBeVisible()
  })

  it('matches rows case-insensitively but menus by exact substring', async () => {
    renderApp()
    await userEvent.type(await openSearch(), 'nice')
    expect(resultTexts()).toEqual([
      '모노마켓 mono000001 · 사업자·가맹점',
      '데일리 커피 daily00003 · 사업자·가맹점',
      'NICE SRC-001 · 원천사·기준정보',
      'NICE ACQ-20261102-01 · 매입·대사',
      'NICE · 영중소 ACQ-20261102-03 · 매입·대사',
    ])
  })

  it('caps the results at 20 (app.js results.slice(0,20))', async () => {
    renderApp()
    // '0'은 메뉴명에는 없고 샘플 26행 모두에 있다 → conf 순서로 앞 20건만(risk 2번째 행까지).
    await userEvent.type(await openSearch(), '0')
    const texts = resultTexts()
    expect(texts).toHaveLength(20)
    expect(texts[0]).toBe('모노마켓 mono000001 · 사업자·가맹점')
    expect(texts[19]).toBe('모노마켓 RM-20261102-02 · 리스크 관리')
    expect(texts.some((t) => t?.includes('JOB-'))).toBe(false)
  })

  it('shows the empty state when nothing matches', async () => {
    renderApp()
    await userEvent.type(await openSearch(), '존재하지않는검색어')
    expect(resultTexts()).toEqual([])
    expect(document.querySelector('#globalresults .empty')).toHaveTextContent('검색 결과가 없어요')
  })
})

describe('SourceFormPanel (app.js sourceForm/saveSource)', () => {
  it('prepends a demo source row, goes back to the reset source list and shows the toast', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp('#source')
    await userEvent.click(screen.getByRole('tab', { name: '수수료·계약' }))
    await userEvent.type(screen.getByLabelText('검색'), 'NICE')
    expect(bodyRows()).toHaveLength(1)
    await userEvent.click(screen.getByRole('button', { name: '원천사 등록' }))
    const dialog = screen.getByRole('dialog')
    expect(within(dialog).getByRole('heading', { level: 2, name: '원천사 등록' })).toBeInTheDocument()
    await userEvent.type(within(dialog).getByLabelText('원천사명'), '데모페이')
    await userEvent.selectOptions(within(dialog).getByLabelText('결제수단'), '가상계좌')
    await userEvent.type(within(dialog).getByLabelText('원천사 발급 가맹점번호'), 'demo_vacct_9')
    await userEvent.click(within(dialog).getByRole('button', { name: '데모 등록' }))

    // go('source'): 패널 닫힘, 탭·검색 초기화, hash 동기화
    expect(overlay()).not.toBeVisible()
    expect(window.location.hash).toBe('#source')
    expect(screen.getByRole('tab', { name: '원천사 연결·채번' })).toHaveClass('on')
    expect(screen.getByLabelText('검색')).toHaveValue('')
    // conf.source.rows.unshift([name, 'SRC-DEMO-'+Date.now() 끝 5자리, method, mid, '설정 대기', '김 운영'])
    const rows = bodyRows()
    expect(rows).toHaveLength(4)
    expect(cellTexts(rows[0]!)).toEqual(['데모페이SRC-DEMO-23456', '가상계좌', 'demo_vacct_9', '설정 대기', '김 운영', '›'])
    expect(cellTexts(rows[1]!)[0]).toBe('NICESRC-001')
    expect(toastEl()).toHaveTextContent('원천사 데모 기준정보를 등록했어요')
    expect(toastEl()).toHaveAttribute('data-open')
    expect(fetch).toHaveBeenCalledTimes(1) // legacy.json만 — 등록은 요청을 보내지 않는다
  })
})

describe('Settlement detail drawer (app.js detail("settle")/submitReview)', () => {
  const action = () => within(screen.getByRole('dialog')).getByRole('button', { name: '지급 승인 요청 시연' })

  it.each([
    ['ST-20261102-001', '승인 대기', false],
    ['ST-20261102-002', '검증 완료', false],
    ['ST-20261102-003', '지급 보류', true],
    ['ST-20261102-004', '지급 완료', true],
  ])('%s (%s) action disabled=%s — /보류|완료/ && !검증', async (key, _status, disabled) => {
    renderApp('#settle')
    await userEvent.click(within(document.getElementById('results')!).getByText(key))
    if (disabled) expect(action()).toBeDisabled()
    else expect(action()).toBeEnabled()
  })

  it('keeps the drawer open with a toast while the reason is missing, then records and closes', async () => {
    renderApp('#settle')
    await userEvent.click(within(document.getElementById('results')!).getByText('ST-20261102-001'))
    await userEvent.click(action())
    expect(toastEl()).toHaveTextContent('검토 사유를 입력해 주세요')
    expect(overlay()).toBeVisible()
    // 공백만 입력해도 사유 없음(value.trim())
    await userEvent.type(screen.getByLabelText('검토 사유'), '   ')
    await userEvent.click(action())
    expect(toastEl()).toHaveTextContent('검토 사유를 입력해 주세요')
    expect(overlay()).toBeVisible()
    await userEvent.clear(screen.getByLabelText('검토 사유'))
    await userEvent.type(screen.getByLabelText('검토 사유'), '금액·계좌 검증 확인')
    await userEvent.click(action())
    expect(toastEl()).toHaveTextContent('데모 검토 이력을 기록했어요')
    expect(overlay()).not.toBeVisible()
    expect(fetch).toHaveBeenCalledTimes(1)
  })
})

describe('CSV export (app.js exportRows)', () => {
  const OBJECT_URL = 'blob:uiref-test/1'
  let blobs: Blob[]
  let downloads: { href: string; download: string }[]
  let revoke: ReturnType<typeof vi.fn>
  const saved: Partial<Record<'createObjectURL' | 'revokeObjectURL', PropertyDescriptor>> = {}

  function readBytes(blob: Blob): Promise<Uint8Array> {
    return new Promise((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(new Uint8Array(r.result as ArrayBuffer))
      r.onerror = () => reject(r.error)
      r.readAsArrayBuffer(blob)
    })
  }

  async function lastCsv() {
    const blob = blobs[blobs.length - 1]
    if (!blob) throw new Error('URL.createObjectURL was not called')
    const bytes = await readBytes(blob)
    return { type: blob.type, bom: [...bytes.slice(0, 3)], text: new TextDecoder('utf-8', { ignoreBOM: true }).decode(bytes) }
  }

  beforeEach(() => {
    blobs = []
    downloads = []
    revoke = vi.fn()
    for (const k of ['createObjectURL', 'revokeObjectURL'] as const) saved[k] = Object.getOwnPropertyDescriptor(URL, k)
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      writable: true,
      value: vi.fn((b: Blob) => {
        blobs.push(b)
        return OBJECT_URL
      }),
    })
    Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, writable: true, value: revoke })
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloads.push({ href: this.getAttribute('href') ?? '', download: this.download })
    })
  })

  afterEach(() => {
    for (const k of ['createObjectURL', 'revokeObjectURL'] as const) {
      const d = saved[k]
      if (d) Object.defineProperty(URL, k, d)
      else delete (URL as unknown as Record<string, unknown>)[k]
    }
  })

  it('downloads the currently filtered rows as BOM + quoted CRLF CSV named pg-<page>-sample.csv', async () => {
    renderApp('#payment')
    await userEvent.click(screen.getByRole('tab', { name: '실패·취소요청' }))
    await userEvent.click(screen.getByRole('button', { name: '↓ CSV' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-payment-sample.csv' }])
    expect(revoke).toHaveBeenCalledWith(OBJECT_URL)
    const csv = await lastCsv()
    expect(csv.type).toBe('text/csv;charset=utf-8')
    expect(csv.bom).toEqual([0xef, 0xbb, 0xbf])
    expect(csv.text).toBe(
      '﻿"가맹점 / 거래번호","결제수단","거래일시","상태","금액"\r\n' + '"오브젝트 스토어 / T202611020002","신용카드","11.02 14:28:12","부분 취소","64,000원"',
    )
    expect(toastEl()).toHaveTextContent('현재 조회 결과를 내보냈어요')
    expect(fetch).toHaveBeenCalledTimes(1)
  })

  it('exports the header only when no rows match, and the "내보내기" primary action exports too', async () => {
    renderApp('#report')
    await userEvent.type(screen.getByLabelText('검색'), '없는행')
    await userEvent.click(screen.getByRole('button', { name: '조회 결과 내보내기' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-report-sample.csv' }])
    expect((await lastCsv()).text).toBe('﻿"구분 / 기준월","매출액","원가","상태","수익"')
  })

  it('doubles embedded quotes (app.js String(s).replace(/"/g, \'""\'))', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp('#source')
    await userEvent.click(screen.getByRole('button', { name: '원천사 등록' }))
    await userEvent.type(screen.getByLabelText('원천사명'), 'A"B페이')
    await userEvent.type(screen.getByLabelText('원천사 발급 가맹점번호'), 'ab_1')
    await userEvent.click(screen.getByRole('button', { name: '데모 등록' }))
    await userEvent.click(screen.getByRole('button', { name: '↓ CSV' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-source-sample.csv' }])
    expect((await lastCsv()).text.split('\r\n')[1]).toBe('"A""B페이 / SRC-DEMO-23456","신용카드","ab_1","설정 대기","김 운영"')
  })
})

describe('detail drawer footnote (원본 style="margin-top:28px" CSP 대체)', () => {
  it('marks the last drawer paragraph with the dedicated class that carries margin-top:28px', async () => {
    renderApp('#payment')
    await userEvent.click(screen.getByText('T202611020001'))
    const note = screen.getByRole('dialog').lastElementChild!
    expect(note.tagName).toBe('P')
    expect(note).toHaveClass('subtle', 'uiref-drawer-note')
    expect(note).toHaveTextContent('화면 시연용 데이터 · 실제 시스템에 반영되지 않습니다.')
    const csp = readFileSync('src/uiref/uiref-csp.css', 'utf8')
    expect(csp).toContain('.uiref-drawer-note{margin-top:28px}')
    expect(csp).not.toContain(':last-child')
    expect(readFileSync('src/uiref/style.css', 'utf8')).not.toContain('uiref-drawer-note')
  })
})

describe('toast timer (app.js toast: 2.8초 표시)', () => {
  it('hides the toast 2.8s after it is shown', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    renderApp('#access')
    fireEvent.click(screen.getByRole('button', { name: '데모 권한 저장' }))
    expect(toastEl()).toHaveAttribute('data-open')
    act(() => vi.advanceTimersByTime(2799))
    expect(toastEl()).toHaveAttribute('data-open')
    act(() => vi.advanceTimersByTime(1))
    expect(toastEl()).not.toHaveAttribute('data-open')
  })

  it('lets the earlier toast timer hide a newer toast at t=2800, as app.js does (timers are not reset)', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    renderApp('#access')
    const save = screen.getByRole('button', { name: '데모 권한 저장' })
    fireEvent.click(save) // t=0 → 첫 timer 2800
    act(() => vi.advanceTimersByTime(1000))
    fireEvent.click(save) // t=1000 → 두 번째 timer 3800
    expect(toastEl()).toHaveAttribute('data-open')
    act(() => vi.advanceTimersByTime(1799)) // t=2799
    expect(toastEl()).toHaveAttribute('data-open')
    act(() => vi.advanceTimersByTime(1)) // t=2800: 첫 timer가 두 번째 toast도 닫는다
    expect(toastEl()).not.toHaveAttribute('data-open')
  })

  it('clears every pending toast timer on unmount', () => {
    const set = vi.spyOn(window, 'setTimeout')
    const view = renderApp('#access')
    const save = screen.getByRole('button', { name: '데모 권한 저장' })
    fireEvent.click(save)
    fireEvent.click(save)
    const ids = set.mock.calls.flatMap((c, i) => (c[1] === 2800 ? [set.mock.results[i]!.value] : []))
    expect(ids).toHaveLength(2)
    const clear = vi.spyOn(window, 'clearTimeout')
    view.unmount()
    for (const id of ids) expect(clear).toHaveBeenCalledWith(id)
  })
})
