import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { TransactionsScreen } from './TransactionsScreen'
import type { AdminApi, TransactionDetail, TransactionListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'
import { expectDetailRows, panelDetailList } from '../test/detailRows'
import { domOccurrences } from '../test/domScan'

/**
 * ADMIN-UI-WORKSPACE-001 거래 조회(3번째 화면 — "모양만"): 참고 시안 moduleView(카드 안 탭 → .filters → .tablewrap → .tablebottom)와 detail(eyebrow·제목·카드)을
 * 실제 거래 조회에 입힌다. 기능·API 호출·오류 처리·TXN-LIST-ENRICH-001 가맹점/결제수단 열은 TransactionsScreen.test.tsx가 그대로 지킨다.
 * Demo 식별자(PAYMENT 형식 — 접두사 + 26자)는 업무 의미 없는 고정값이다.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const PAY1 = id('pay', 1)
const MID = id('mid', 1)
const NOW = Date.parse('2026-11-04T01:00:00.000Z')
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/

const item: TransactionListItem = {
  paymentId: PAY1, environment: 'TEST', merchantAccountId: MID, merchantOrderId: 'order-77', orderNamePreview: '가나다라마바사아자차', orderNameTruncated: true,
  amountMinor: 10000, currency: 'KRW', paymentMethod: 'CARD', status: 'AUTHORIZED', createdAt: '2026-11-01T01:02:03.456Z', merchant: { name: '데모상점', code: 'demoshop01' },
}

/** 상세 한 건(승인 1, 거래 사실 1, 취소 0, 잔액 없음, hold 있음 — 금액은 승인액과 같다). */
const detail: TransactionDetail = {
  payment: {
    paymentId: PAY1, environment: 'TEST', merchantAccountId: MID, merchantSiteId: 'demo-site-001', merchantOrderId: 'order-77', orderName: '가나다라마바사아자차카타',
    amountMinor: 10000, currency: 'KRW', paymentMethod: 'CARD', status: 'AUTHORIZED', createdAt: '2026-11-01T01:02:03.456Z',
  },
  onHold: true,
  terminationEvidence: [],
  authorization: { authorizationId: id('pau', 1), status: 'AUTHORIZED', failureEvidence: null, planKind: 'LUMP_SUM', installmentMonths: null, cardType: 'CREDIT', openedAt: '2026-11-01T01:02:04.000Z' },
  transactions: [
    {
      transactionId: id('ptx', 1), type: 'AUTHORIZATION', amountMinor: 10000, currency: 'KRW', planKind: 'LUMP_SUM', installmentMonths: null, cardType: 'CREDIT',
      approvalNumber: '12345678', providerTransactionId: 'SIMPTX-000111', cancellationId: null, originalTransactionId: null, cancelScope: null,
      institutionTransactionDate: '2026-11-01', institutionBusinessDate: null, confirmedAt: '2026-11-01T01:02:05.000Z', recordedBy: 'RESOLUTION',
    },
  ],
  cancellations: [],
  returnBalance: null,
}

function signedIn(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '관리자', roles: ['VIEWER'] })),
    approvals: vi.fn(async () => ({ approvals: [] })),
    ...overrides,
  })
  return { api, view: render(<App api={api} />) }
}

describe('ADMIN-UI-WORKSPACE-001: 결제 관리 › 거래 조회 in the reference list layout', () => {
  it('flush in the menu card: tabpanel > .mapnote(h2) → form.filters → .cardnote; the list is .tablewrap → .tablebottom with the reference row shape', async () => {
    signedIn({
      transactionSearch: vi.fn(async () => ({ items: [item], nextCursor: { createdAt: item.createdAt, paymentId: PAY1 }, merchantLabels: 'OK' as const })),
      transactionDetail: vi.fn(async () => detail),
    })
    await userEvent.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '결제 관리' }))
    await userEvent.click(screen.getByRole('tab', { name: '거래 조회' }))
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    // 시안 구조로 옮긴 화면은 탭 패널 바로 안에 그린다(안쪽 여백 칸 없음 — MenuCard flush).
    expect(Array.from(panel.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))).toEqual(['div.mapnote', 'form.filters.many', 'div.cardnote'])
    expect(within(panel.querySelector('.mapnote') as HTMLElement).getByRole('heading', { level: 2, name: '거래 조회' })).toBeInTheDocument()

    const form = panel.querySelector('form.filters') as HTMLElement
    for (const name of ['조회 방식', '시작일(KST)', '종료일(KST)', '환경', 'MID', '결제 상태']) expect(within(form).getByLabelText(name)).toBeInTheDocument()
    // 기간 안내(최대 31일)는 카드 안내 줄에 두고 날짜 입력란에 aria-describedby로 잇는다.
    // 기대값 변경(ADMIN-UI-WORKSPACE-001 거래 조회 리뷰 MINOR-2): 날짜 칸에는 placeholder가 없어 안내 문구에 "시작일 ~ 종료일"을 넣었다 — 문구만, 연결(aria-describedby) 단언은 그대로.
    const periodHint = '조회 기간(시작일 ~ 종료일): KST 달력일 기준, 최대 31일(Demo 기준).'
    expect(within(form).getByLabelText('시작일(KST)')).toHaveAccessibleDescription(periodHint)
    // 리뷰 NIT-5: 종료일 칸도 같은 안내에 이어진다.
    expect(within(form).getByLabelText('종료일(KST)')).toHaveAccessibleDescription(periodHint)
    expect(within(form).getByRole('button', { name: '검색' })).toHaveClass('btn', 'primary')

    await userEvent.click(within(form).getByRole('button', { name: '검색' }))
    const row = await screen.findByRole('row', { name: PAY1 })
    const wrap = panel.querySelector('.tablewrap')!
    expect(wrap.querySelector('table')).toHaveAccessibleName('거래 목록')
    expect(Array.from(wrap.querySelectorAll('th')).map((th) => th.textContent)).toEqual(['주문 / 결제 ID', '가맹점', '결제수단', '생성 시각', '상태', '금액', '작업'])
    expect(wrap.querySelectorAll('th')[5]).toHaveClass('amount')
    const cells = row.querySelectorAll('td')
    expect(cells[0]!.querySelector('strong')).toHaveTextContent('order-77')
    expect(Array.from(cells[0]!.querySelectorAll('small')).map((s) => s.textContent)).toEqual(['가나다라마바사아자차…', PAY1])
    expect(cells[1]!.querySelector('strong')).toHaveTextContent('데모상점 · demoshop01')
    expect(cells[1]!.querySelector('small')).toHaveTextContent(`TEST · ${MID}`)
    expect(cells[4]!.querySelector('.tag.green')).toHaveTextContent('승인 완료')
    expect(cells[5]).toHaveClass('amount')
    expect(cells[5]).toHaveTextContent('10,000원')
    expect(within(cells[6]!).getByRole('button', { name: '상세' })).toHaveClass('btn')
    expect(wrap.nextElementSibling).toHaveClass('tablebottom')
    expect(within(wrap.nextElementSibling as HTMLElement).getByRole('button', { name: '다음' })).toHaveClass('btn')

    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const area = (await screen.findByRole('region', { name: '결제' })).closest('.detailarea')!
    expect(area.parentElement).toBe(panel)
    expect(row).toHaveClass('selected')
    expect(within(area as HTMLElement).getByRole('heading', { level: 3 })).toHaveTextContent(`거래 상세 ${PAY1}`)
    // 상세 패널은 시안 .card > .cardtop(h4) → .cardbody, 표는 .tablewrap 안.
    for (const name of ['결제', '승인', '거래 사실', '취소 이력', '반환 잔액']) {
      const region = screen.getByRole('region', { name })
      expect(region).toHaveClass('card')
      expect(region.querySelector(':scope > .cardtop > h4')).toHaveTextContent(name)
      expect(region.querySelector(':scope > .cardbody')).not.toBeNull()
    }
    expect(screen.getByRole('table', { name: '거래 사실 목록' }).parentElement).toHaveClass('tablewrap')
    expect(screen.getByRole('table', { name: '취소 이력 목록' }).parentElement).toHaveClass('tablewrap')
    expect(within(screen.getByRole('region', { name: '결제' })).getByText('보류(hold) 중')).toHaveClass('tag', 'red')
    expect(within(screen.getByRole('region', { name: '취소 이력' })).getByText('취소 이력이 없습니다.')).toHaveClass('empty')
    // 내부 키(mer_·biz_)는 화면 어디에도 없다(D-ADMIN-UI-004 ③, D-MCODE-003).
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('the payment-id and order modes keep the reference filter row (only the fields change); an input problem is one alert in the card note', async () => {
    const { container } = render(<TransactionsScreen api={fakeApi({ transactionSearch: vi.fn() })} now={() => NOW} />)
    const form = container.querySelector('form.filters') as HTMLElement
    await userEvent.selectOptions(within(form).getByLabelText('조회 방식'), 'payment')
    expect(Array.from(form.querySelectorAll('input, select')).map((el) => el.getAttribute('aria-label'))).toEqual(['조회 방식', '결제 ID'])
    // 리뷰 NIT-1: 안내·오류가 없으면 .cardnote를 그리지 않는다(빈 20px 여백 없음).
    expect(container.querySelector('.cardnote')).toBeNull()
    await userEvent.click(within(form).getByRole('button', { name: '검색' }))
    expect(container.querySelector('.cardnote [role="alert"]')).toHaveTextContent('결제 ID를 입력해 주세요.')
    expect(container.querySelector('.cardnote [role="alert"]')).toHaveClass('error')
    await userEvent.selectOptions(within(form).getByLabelText('조회 방식'), 'order')
    expect(Array.from(form.querySelectorAll('input, select')).map((el) => el.getAttribute('aria-label'))).toEqual(['조회 방식', '환경', 'MID', '가맹점 주문번호'])
    expect(container.querySelector('.cardnote')).toBeNull()
  })

  it('a label-unavailable page shows the environment · MID without a merchant name and one note inside the table wrapper', async () => {
    const { container } = render(
      <TransactionsScreen api={fakeApi({ transactionSearch: vi.fn(async () => ({ items: [{ ...item, merchant: null }], nextCursor: null, merchantLabels: 'UNAVAILABLE' as const })) })} now={() => NOW} />,
    )
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    const row = await screen.findByRole('row', { name: PAY1 })
    expect(row.querySelectorAll('td')[1]!.querySelector('strong')).toBeNull()
    expect(row.querySelectorAll('td')[1]).toHaveTextContent(`TEST · ${MID}`)
    expect(container.querySelector('.tablewrap > p.mapnote[role="note"]')).toHaveTextContent('가맹점 정보를 불러오지 못했어요')
    expect(container.querySelector('.tablebottom')).not.toBeNull()
  })

  it('stylesheet: the filter-row wrap rules for this screen live in the PayCore adaptation block (no new color)', () => {
    const adaptation = WORKSPACE_CSS.indexOf('PayCore 적응 규칙')
    const light = WORKSPACE_CSS.indexOf('라이트 대비 보정')
    const block = WORKSPACE_CSS.slice(adaptation, light)
    // 기대값 변경(리뷰 MINOR-1 — 이 시험은 이번 화면에서 새로 만든 것): 줄바꿈은 모든 폭에서(미디어 조건 밖, 줄 첫머리 규칙),
    // 입력란·선택은 원본 min-width:0 대신 고유 폭(min-width:auto — 새 크기 값 아님)이라 1101~1300px에서도 날짜가 잘리지 않고 다음 줄로 간다.
    expect(block).toMatch(/^\.filters\.many\{flex-wrap:wrap\}$/m)
    expect(block).toMatch(/^\.filters\.many input,\.filters\.many select\{min-width:auto\}$/m)
    expect(block).toContain('@media(max-width:1100px){.filters.many input,.filters.many select{flex:1 1 40%}')
    // 리뷰 NIT-3: 아래 줄 간격 12px(원본 .quick·.rolebar 값)·세로 가운데.
    expect(block).toMatch(/^\.tablebottom\{gap:12px;align-items:center\}$/m)
    const rules = WORKSPACE_CSS.slice(adaptation, light).split('\n').filter((line) => line.includes('.filters.many'))
    expect(rules.length).toBeGreaterThan(0)
    for (const rule of rules) expect(rule).not.toMatch(/#[0-9a-f]{3,8}\b/i)
  })
})

/**
 * LAYOUT-ROLLOUT B6(사용자 결정 2026-11-19 — 상세 일관성): 거래 상세의 결제 · 승인 · 반환 잔액 정의 목록을 사업자 상세 DetailRow 줄로.
 * 바깥 Panel(.card > .cardtop h4 + .cardbody)과 거래 사실 · 취소 이력 표(.tablewrap)는 그대로 — 위 시험이 지킨다.
 */
describe('LAYOUT-ROLLOUT B6: 거래 상세 정의 목록 = 사업자 상세 줄(DetailRow)', () => {
  it('결제 · 승인 · 반환 잔액은 140px 이름 열 줄, 상태 태그는 dd 안 감싼 칸에(늘어나지 않음), style 속성 없음', async () => {
    const withBalance: TransactionDetail = {
      ...detail,
      returnBalance: { currency: 'KRW', authorizedMinor: 10000, completedMinor: 3000, reservedMinor: 2000, forcedMinor: 0, revision: 2 },
    }
    render(
      <TransactionsScreen
        api={fakeApi({ transactionSearch: vi.fn(async () => ({ items: [item], nextCursor: null, merchantLabels: 'OK' as const })), transactionDetail: vi.fn(async () => withBalance) })}
        now={() => NOW}
      />,
    )
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: PAY1 })).getByRole('button', { name: '상세' }))
    const payment = await screen.findByRole('region', { name: '결제' })
    expectDetailRows(panelDetailList(payment, '결제'), ['결제 ID', '상태', '금액', '생성 시각', '환경', 'MID', 'Site', '가맹점 주문번호', '주문명', '결제수단', '종료 근거'])
    const authorization = screen.getByRole('region', { name: '승인' })
    expectDetailRows(panelDetailList(authorization, '승인'), ['승인 ID', '승인 상태', '할부', '카드 종류', '시작 시각'])
    const balance = screen.getByRole('region', { name: '반환 잔액' })
    expectDetailRows(panelDetailList(balance, '반환 잔액'), ['승인액', '취소 완료', '취소 진행 중(예약)', '강제 반영', 'revision'])
    // 값은 그대로: 상태 태그 · 보류 태그 · 금액.
    expect(within(payment).getByText('보류(hold) 중')).toHaveClass('tag', 'red')
    expect(within(authorization).getByText('승인 완료')).toHaveClass('tag', 'green')
    expect(within(balance).getByText('3,000원')).toBeInTheDocument()
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })
})
