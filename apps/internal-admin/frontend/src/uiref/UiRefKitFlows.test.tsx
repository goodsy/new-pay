import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { act, fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { WorkspaceProvider } from './store/WorkspaceProvider'

// 기대값 출처: reference/original/app.js 의 wizard()/wizardNext()·sourceForm()/saveSource()·detail()/submitReview()·exportRows()·accessView()·mapView()·toast().
// 위저드·원천사 등록·권한 저장은 화면 시연이다 — 행은 메모리에만 추가되고 요청을 보내지 않는다.

function renderApp(hash = '') {
  window.history.replaceState(null, '', `${window.location.pathname}${hash}`)
  return render(
    <WorkspaceProvider>
      <App />
    </WorkspaceProvider>,
  )
}

const overlay = () => document.getElementById('overlay')
const toastEl = () => document.getElementById('toast')!
const bodyRows = () => [...document.querySelectorAll('#results tbody tr')]
const cellTexts = (tr: Element) => [...tr.querySelectorAll('td')].map((td) => td.textContent)

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
  vi.useRealTimers()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  window.history.replaceState(null, '', window.location.pathname)
})

describe('사업자 등록 위저드 4단계 (SCREENS §4)', () => {
  const current = () => [...document.querySelectorAll('.wizard .current')].map((s) => s.textContent)
  // getByRole 은 큰 DOM 에서 느리다 — Drawer 안에서만 찾는다
  const inDrawer = () => within(document.querySelector<HTMLElement>('.drawer')!)
  const next = () => user.click(inDrawer().getByRole('button', { name: '다음 단계 →' }))

  async function openWizardAndFillStep1(biz: string) {
    await user.click(screen.getByText('＋ 사업자 등록'))
    await user.type(screen.getByLabelText('사업자명 *'), '테스트상점')
    await user.type(screen.getByLabelText('사업자등록번호 *'), biz)
    await user.type(screen.getByLabelText('담당자 *'), '김 운영')
  }

  it('step 1 blocks empty required fields and a malformed 사업자등록번호 (native required/pattern)', async () => {
    renderApp('#home')
    await user.click(screen.getByText('＋ 사업자 등록'))
    const drawer = screen.getByRole('dialog')
    expect(drawer).toHaveClass('drawer')
    expect(within(drawer).getByText('MERCHANT ONBOARDING')).toHaveClass('eyebrow')
    expect([...drawer.querySelectorAll('.wizard span')].map((s) => s.textContent)).toEqual([
      '1. 사업자 정보', '2. 계약·원천사', '3. 가맹점 설정', '4. 검토·등록',
    ])
    expect(current()).toEqual(['1. 사업자 정보'])
    expect(inDrawer().queryByRole('button', { name: '이전' })).toBeNull()
    await next()
    expect(current()).toEqual(['1. 사업자 정보'])
    await user.keyboard('{Escape}')
    await openWizardAndFillStep1('1234567890')
    await next()
    expect(current()).toEqual(['1. 사업자 정보'])
    expect(screen.getByLabelText('사업자등록번호 *')).toBeInvalid()
    await user.clear(screen.getByLabelText('사업자등록번호 *'))
    await user.type(screen.getByLabelText('사업자등록번호 *'), '123-45-67890')
    await next()
    expect(current()).toEqual(['1. 사업자 정보', '2. 계약·원천사'])
  })

  it('validates the fee, keeps answers across 이전, and registers a demo MID row with a toast', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp('#home')
    await openWizardAndFillStep1('123-45-67890')
    await next()

    // 2단계
    expect(current()).toEqual(['1. 사업자 정보', '2. 계약·원천사'])
    expect(screen.getByLabelText('계약 유형')).toHaveValue('일반 가맹점')
    await user.selectOptions(screen.getByLabelText('원천사'), '키움페이')
    await next()

    // 3단계: 서비스명 기본값 = 사업자명, 수수료율 기본 2.4 (number 0~100)
    expect(current()).toHaveLength(3)
    expect(screen.getByLabelText('서비스명 *')).toHaveValue('테스트상점')
    const fee = screen.getByLabelText('카드 수수료율 (%, VAT 별도) *')
    expect(fee).toHaveValue(2.4)
    expect(fee).toHaveAttribute('step', '0.01')
    await user.clear(fee)
    await next()
    expect(current()).toHaveLength(3)
    await user.type(fee, '101')
    await next()
    expect(current()).toHaveLength(3)
    await user.clear(fee)
    await user.type(fee, '2.4')
    await user.selectOptions(screen.getByLabelText('정산 주기'), '반월')

    // 이전 → 2단계 선택값 유지. 원본처럼 '이전'은 현재 단계 입력을 저장하지 않는다(wiz--) → 3단계로 돌아오면 정산 주기는 기본값
    await user.click(inDrawer().getByRole('button', { name: '이전' }))
    expect(current()).toHaveLength(2)
    expect(screen.getByLabelText('원천사')).toHaveValue('키움페이')
    await next()
    expect(screen.getByLabelText('정산 주기')).toHaveValue('D+3')
    expect(screen.getByLabelText('서비스명 *')).toHaveValue('테스트상점')
    await user.selectOptions(screen.getByLabelText('정산 주기'), '반월')
    await next()

    // 4단계 검토
    expect(current()).toHaveLength(4)
    const grid = [...document.querySelectorAll('.drawer .detailgrid > div')].map((d) => `${d.querySelector('label')!.textContent}=${d.querySelector('strong')!.textContent}`)
    expect(grid).toEqual(['사업자명=테스트상점', '사업자번호=123-45-67890', '원천사=키움페이', '정산 주기=반월', '서비스명=테스트상점', '수수료=2.4% · VAT 별도'])
    await user.click(inDrawer().getByRole('button', { name: '등록하고 데모 MID 발급' }))

    // 등록: merchant 맨 앞 [사업자명, demo######, 사업자번호, 원천사, 심사 중, 담당자] → /merchant → 토스트
    expect(overlay()).toBeNull()
    expect(window.location.hash).toBe('#merchant')
    expect(cellTexts(bodyRows()[0]!)).toEqual(['테스트상점demo123456', '123-45-67890', '키움페이', '심사 중', '김 운영', '›'])
    expect(bodyRows()).toHaveLength(5)
    expect(toastEl()).toHaveTextContent('사업자를 등록하고 데모 MID demo123456를 발급했어요')
    expect(toastEl()).toHaveClass('show')
  })

  it('starts empty every time it is reopened (원본 startWizard: form={}, wiz=0)', async () => {
    renderApp('#merchant')
    await user.click(screen.getByRole('button', { name: '사업자 등록' }))
    await user.type(screen.getByLabelText('사업자명 *'), '임시')
    await user.keyboard('{Escape}')
    await user.click(screen.getByRole('button', { name: '사업자 등록' }))
    expect(screen.getByLabelText('사업자명 *')).toHaveValue('')
    expect(current()).toEqual(['1. 사업자 정보'])
  })
})

describe('원천사 등록 (SCREENS §5)', () => {
  it('requires the fields, prepends a SRC-DEMO row, returns to the reset source list and toasts', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp('#source')
    await user.click(screen.getByRole('tab', { name: '수수료·계약' }))
    await user.type(screen.getByLabelText('검색'), 'NICE')
    await user.click(screen.getByRole('button', { name: '원천사 등록' }))
    const dialog = screen.getByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: '데모 등록' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.type(within(dialog).getByLabelText('원천사명'), '데모페이')
    await user.selectOptions(within(dialog).getByLabelText('결제수단'), '가상계좌')
    await user.type(within(dialog).getByLabelText('원천사 발급 가맹점번호'), 'demo_vacct_9')
    await user.click(within(dialog).getByRole('button', { name: '데모 등록' }))
    expect(overlay()).toBeNull()
    expect(window.location.hash).toBe('#source')
    expect(screen.getByRole('tab', { name: '원천사 연결·채번' })).toHaveClass('on')
    expect(screen.getByLabelText('검색')).toHaveValue('')
    expect(bodyRows()).toHaveLength(4)
    expect(cellTexts(bodyRows()[0]!)).toEqual(['데모페이SRC-DEMO-23456', '가상계좌', 'demo_vacct_9', '설정 대기', '김 운영', '›'])
    expect(toastEl()).toHaveTextContent('원천사 데모 기준정보를 등록했어요')
  })
})

describe('정산 상세 — 지급 승인 요청 시연 (실제 승인/지급 없음)', () => {
  const action = () => within(screen.getByRole('dialog')).getByRole('button', { name: '지급 승인 요청 시연' })

  it.each([
    ['ST-20261102-001', false],
    ['ST-20261102-002', false],
    ['ST-20261102-003', true],
    ['ST-20261102-004', true],
  ])('%s disabled=%s (/보류|완료/ && !검증)', async (key, disabled) => {
    renderApp('#settle')
    await user.click(within(document.getElementById('results')!).getByText(key))
    if (disabled) expect(action()).toBeDisabled()
    else expect(action()).toBeEnabled()
  })

  it('keeps the drawer with a toast while the reason is blank, then records (demo) and closes', async () => {
    renderApp('#settle')
    await user.click(within(document.getElementById('results')!).getByText('ST-20261102-001'))
    expect(document.querySelectorAll('.drawer .pipe')).toHaveLength(4)
    expect(screen.getByLabelText('검토 사유')).toHaveAttribute('id', 'reason')
    await user.click(action())
    expect(toastEl()).toHaveTextContent('검토 사유를 입력해 주세요')
    expect(overlay()).not.toBeNull()
    await user.type(screen.getByLabelText('검토 사유'), '   ')
    await user.click(action())
    expect(toastEl()).toHaveTextContent('검토 사유를 입력해 주세요')
    expect(overlay()).not.toBeNull()
    await user.type(screen.getByLabelText('검토 사유'), '금액·계좌 검증 확인')
    await user.click(action())
    expect(toastEl()).toHaveTextContent('데모 검토 이력을 기록했어요')
    expect(overlay()).toBeNull()
  })
})

describe('CSV 내보내기 (원본 exportRows: BOM + CRLF, pg-{moduleId}-sample.csv)', () => {
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

  it('downloads the currently filtered rows as BOM + quoted CRLF CSV and toasts', async () => {
    renderApp('#payment')
    await user.click(screen.getByRole('tab', { name: '실패·취소요청' }))
    await user.click(screen.getByRole('button', { name: '↓ CSV' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-payment-sample.csv' }])
    expect(revoke).toHaveBeenCalledWith(OBJECT_URL)
    const csv = await lastCsv()
    expect(csv.type).toBe('text/csv;charset=utf-8')
    expect(csv.bom).toEqual([0xef, 0xbb, 0xbf])
    expect(csv.text).toBe(
      '﻿"가맹점 / 거래번호","결제수단","거래일시","상태","금액"\r\n' + '"오브젝트 스토어 / T202611020002","신용카드","11.02 14:28:12","부분 취소","64,000원"',
    )
    expect(toastEl()).toHaveTextContent('현재 조회 결과를 내보냈어요')
  })

  it('uses CRLF between every row and names the file after the module (ops primary action)', async () => {
    renderApp('#ops')
    await user.click(screen.getByRole('button', { name: '실행 이력 내보내기' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-ops-sample.csv' }])
    const lines = (await lastCsv()).text.split('\r\n')
    expect(lines).toHaveLength(4)
    expect(lines.every((l) => !l.includes('\n'))).toBe(true)
    expect(lines[2]).toBe('"거래내역 메일 발송 / JOB-20261102-02","11.02 09:00","238 / 240","재시도 필요","운영 지원"')
  })

  it('exports the header only when nothing matches (report "조회 결과 내보내기")', async () => {
    renderApp('#report')
    await user.type(screen.getByLabelText('검색'), '없는행')
    await user.click(screen.getByRole('button', { name: '조회 결과 내보내기' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-report-sample.csv' }])
    expect((await lastCsv()).text).toBe('﻿"구분 / 기준월","매출액","원가","상태","수익"')
  })

  it('doubles embedded quotes', async () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_700_000_123_456)
    renderApp('#source')
    await user.click(screen.getByRole('button', { name: '원천사 등록' }))
    await user.type(screen.getByLabelText('원천사명'), 'A"B페이')
    await user.type(screen.getByLabelText('원천사 발급 가맹점번호'), 'ab_1')
    await user.click(screen.getByRole('button', { name: '데모 등록' }))
    await user.click(screen.getByRole('button', { name: '↓ CSV' }))
    expect(downloads).toEqual([{ href: OBJECT_URL, download: 'pg-source-sample.csv' }])
    expect((await lastCsv()).text.split('\r\n')[1]).toBe('"A""B페이 / SRC-DEMO-23456","신용카드","ab_1","설정 대기","김 운영"')
  })
})

describe('계정·권한 (SCREENS §7 — UI 시연, 실제 접근 통제 없음)', () => {
  it('applies the default checks per role, keeps toggles per role in memory and only toasts on save', async () => {
    renderApp('#access')
    expect(document.querySelector('main > .card')!.tagName).toBe('DIV')
    expect([...document.querySelectorAll('table.matrix thead th')].map((t) => t.textContent)).toEqual(['업무', '조회', '등록·수정', '승인', '내보내기'])
    expect(document.querySelectorAll('table.matrix tbody tr')).toHaveLength(8)
    expect(screen.getByLabelText('결제 관리 조회')).toBeChecked()
    expect(screen.getByLabelText('정산·지급 등록 수정')).not.toBeChecked()
    await user.selectOptions(document.querySelector<HTMLSelectElement>('.rolebar select')!, '정산 담당자')
    expect(screen.getByLabelText('정산·지급 등록 수정')).toBeChecked()
    await user.click(screen.getByLabelText('결제 관리 승인'))
    expect(screen.getByLabelText('결제 관리 승인')).toBeChecked()
    await user.selectOptions(document.querySelector<HTMLSelectElement>('.rolebar select')!, '승인 관리자')
    expect(screen.getByLabelText('결제 관리 승인')).toBeChecked()
    await user.click(screen.getByLabelText('결제 관리 승인'))
    expect(screen.getByLabelText('결제 관리 승인')).not.toBeChecked()
    await user.selectOptions(document.querySelector<HTMLSelectElement>('.rolebar select')!, '정산 담당자')
    expect(screen.getByLabelText('결제 관리 승인')).toBeChecked()
    await user.click(screen.getByRole('button', { name: '데모 권한 저장' }))
    expect(toastEl()).toHaveTextContent('현재 탭의 데모 권한을 저장했어요. 실제 권한에는 반영되지 않습니다.')
    expect(document.querySelector('.card .mapnote')).toHaveTextContent('실제 접근 통제는 서버에서 적용해야 합니다.')
  })

  it('opens the 계정 관리 / 접속·감사 이력 drawers from the tabs while 역할·권한 stays selected', async () => {
    renderApp('#access')
    await user.click(screen.getByRole('tab', { name: '계정 관리' }))
    expect(within(screen.getByRole('dialog')).getByRole('heading', { level: 2, name: '계정 관리' })).toBeInTheDocument()
    expect(document.querySelectorAll('.drawer .timeline p')).toHaveLength(3)
    expect(screen.getByRole('tab', { name: '역할·권한' })).toHaveClass('on')
    await user.click(screen.getByRole('tab', { name: '접속·감사 이력' }))
    expect(document.querySelectorAll('[role="dialog"]')).toHaveLength(1)
    expect(within(screen.getByRole('dialog')).getByText('14:32 · 김 운영 · 가맹점 조회')).toBeInTheDocument()
  })
})

describe('메뉴 개편 지도 (SCREENS §8)', () => {
  const mapRows = () => document.querySelectorAll('#maprows tbody tr')

  it('lists the 165 legacy menus with destinations and filters by name + MENU_ID', async () => {
    renderApp('#map')
    expect(mapRows()).toHaveLength(165)
    expect(screen.getAllByRole('button', { name: '공통 인증 →' }).length).toBeGreaterThan(0)
    await user.type(screen.getByLabelText('레거시 메뉴 검색'), '110101')
    expect(mapRows()).toHaveLength(1)
    expect(mapRows()[0]!.textContent).toContain('계약 진행현황')
    await user.click(within(mapRows()[0] as HTMLElement).getByRole('button', { name: '사업자·가맹점 →' }))
    expect(window.location.hash).toBe('#merchant')
  })
})

describe('토스트 2.8초 (원본 toast — timer 를 지우지 않음)', () => {
  it('hides 2.8s after it is shown', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    renderApp('#access')
    fireEvent.click(screen.getByRole('button', { name: '데모 권한 저장' }))
    expect(toastEl()).toHaveClass('show')
    act(() => vi.advanceTimersByTime(2799))
    expect(toastEl()).toHaveClass('show')
    act(() => vi.advanceTimersByTime(1))
    expect(toastEl()).not.toHaveClass('show')
  })

  it('lets the earlier timer hide a newer toast at t=2800 as app.js does, and clears pending timers on unmount', () => {
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const view = renderApp('#access')
    const save = screen.getByRole('button', { name: '데모 권한 저장' })
    fireEvent.click(save)
    act(() => vi.advanceTimersByTime(1000))
    fireEvent.click(save)
    act(() => vi.advanceTimersByTime(1799))
    expect(toastEl()).toHaveClass('show')
    act(() => vi.advanceTimersByTime(1))
    expect(toastEl()).not.toHaveClass('show')
    expect(vi.getTimerCount()).toBe(1)
    view.unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
