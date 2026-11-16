import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ApprovalView, HomeKpiItem, HomeKpis, ReconciliationOverview, TransactionListItem } from '../api/adminApi'
import { ApiError } from '../api/client'
import { menusFor } from '../navigation'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import { HomeScreen } from './HomeScreen'

/**
 * 운영 홈(ADMIN-UI-WORKSPACE-001 2단계 — 참고 시안 home() 구성, D-ADMIN-HOME-001).
 * 바뀐 단언(구조·문구 — 시안 구성으로 바뀐 것; 호출·수 규칙은 그대로):
 * - 쪽 제목 "안녕하세요, …님" → 시안 "좋은 하루예요, …님".
 * - 현황 카드 3종(승인 대기·내 요청 대기·열린 대사 발견 article) → 시안 KPI 4개(오늘 순승인액·오늘 승인 건수·지급 예정액·확인이 필요한 업무).
 *   출처별 수는 "확인이 필요한 업무" 카드의 내역 줄(승인 N · 내 요청 N · 대사 N)과 지금 처리할 업무 행으로 확인한다.
 * - 바로가기: 이 역할의 모든 탭(region) → 시안 4개 자리(group, 있는 메뉴만).
 * - VIEWER에도 KPI 카드가 있다(거래 조회 권한 — 오늘 순승인액·건수). 승인·대사 호출이 없는 것은 그대로.
 * 기대값 변경(Coordinator Q2 — ADMIN-HOME-KPI-001 §0 카드 이름): KPI 카드 제목·이름 "오늘 승인 금액" → "오늘 순승인액"(이 파일 전체).
 */

/** 승인 요청 한 건(상태만 시험 조건 — 나머지는 표시용 고정값). */
const approval = (approvalId: string, status: string, targetRef: string | null = null): ApprovalView => ({
  approvalId, kind: 'ROLE_GRANT', targetActorId: 'adm_T', targetSnapshot: '{}', grantedRole: 'VIEWER', causeApprovalId: null, requestedBy: 'adm_R',
  requestedByDisplayName: '요청자', requestedAt: '2026-11-01T00:00:00Z', expiresAt: '2026-11-02T00:00:00Z', status, decidedBy: null, decidedByDisplayName: null,
  decidedAt: null, resultCode: null, ...(targetRef ? { targetKind: 'MERCHANT', targetRef } : {}),
} as ApprovalView)
const overview = (openTotal: number): ReconciliationOverview => ({
  openCounts: openTotal ? { MISSING_DECISION: openTotal } : {}, openTotal, findings: [], truncated: false, lastRunAt: null, lastFullScanAt: null, lastResult: null,
})
const me = (roles: string[]) => ({ actorId: 'adm_1', displayName: '김 운영', roles })
/** 고정 시각: 2026-11-15 10:30 KST. */
const NOW = new Date('2026-11-15T01:30:00.000Z')
const PAY = 'pay_01JC0000000000000000000001'
const MID = 'mid_01JC0000000000000000000001'
/** 목록 항목(TXN-LIST-ENRICH-001 — 결제수단·가맹점 라벨 포함, 기본 라벨 "데모상점 · demoshop01"). */
const txn = (
  paymentId: string, status = 'AUTHORIZED', merchant: TransactionListItem['merchant'] = { name: '데모상점', code: 'demoshop01' }, paymentMethod = 'CARD',
): TransactionListItem => ({
  paymentId, environment: 'LIVE', merchantAccountId: MID, merchantOrderId: 'order-77', orderNamePreview: '데모 주문', orderNameTruncated: false,
  amountMinor: 12000, currency: 'KRW', paymentMethod, status, createdAt: '2026-11-15T00:10:00.000Z', merchant,
})
/**
 * KPI 한 행(ADMIN-HOME-KPI-001 §4.1 모양). 금액은 minor unit 10진 문자열 — 시험마다 net을 직접 적어 기대값이 보이게 한다(유효 행은 net = approved − cancelled를 지킨다).
 */
const kpiItem = (environment: string, currency: string, approvedAmountMinor: string, cancelledAmountMinor: string, netApprovedAmountMinor: string, approvedCount: number): HomeKpiItem => ({
  environment, currency, approvedAmountMinor, cancelledAmountMinor, netApprovedAmountMinor, approvedCount,
})
/** KPI 문서: asOf = NOW(10:30 KST), businessDate·from = 그 KST 날짜 0시(§3.1). */
const kpiDoc = (items: HomeKpiItem[]): HomeKpis => ({
  asOf: '2026-11-15T01:30:00.000Z', businessDate: '2026-11-15', timeZone: 'Asia/Seoul', from: '2026-11-14T15:00:00.000Z', items,
})
const noRecent = () => vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const }))

function renderHome(roles: string[], api = fakeApi(), onOpen = vi.fn(), onSessionEnded = vi.fn()) {
  render(<HomeScreen api={api} me={me(roles)} menus={menusFor(roles)} onOpen={onOpen} onSessionEnded={onSessionEnded} now={() => NOW} />)
  return { api, onOpen, onSessionEnded }
}
const stat = (name: string) => screen.getByRole('article', { name })

describe('HomeScreen (운영 홈 — reference home layout, real values only, D-ADMIN-HOME-001)', () => {
  it('reference order: heading(eyebrow, greeting, description | date, 사업자 등록) → stats(4) → split(todo | settlement flow) → recent payments → quick links', async () => {
    const { onOpen } = renderHome(['OPERATOR'], fakeApi({ approvals: vi.fn(async () => ({ approvals: [] })), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(screen.getByRole('heading', { level: 1, name: '좋은 하루예요, 김 운영님' })).toBeInTheDocument()
    expect(screen.getByText('오늘 처리해야 할 업무와 결제 흐름을 한눈에 확인하세요.')).toBeInTheDocument()
    // 날짜 고르기 기능이 없어 오늘(KST) 날짜만 — 누르는 단추가 아니다.
    const date = screen.getByText('2026. 11. 15')
    expect(date.tagName).toBe('TIME')
    expect(date).toHaveAttribute('dateTime', '2026-11-15')
    expect(date.closest('button')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '＋ 사업자 등록' }))
    expect(onOpen).toHaveBeenCalledWith('businessRegistration')
    expect(screen.getAllByRole('article').map((a) => a.getAttribute('aria-label'))).toEqual(['오늘 순승인액', '오늘 승인 건수', '지급 예정액', '확인이 필요한 업무'])
    expect(within(stat('오늘 순승인액')).getByText('오늘 순승인액')).toHaveClass('stitle')
    const main = document.body.firstElementChild!
    const order = Array.from(main.children).map((el) => el.className)
    expect(order).toEqual(['heading', 'stats', 'split', 'card', 'quick'])
    expect(within(main.querySelector('.split') as HTMLElement).getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(['지금 처리할 업무', '오늘의 정산 흐름'])
  })

  // 기대값 변경(Coordinator Q1 — KPI 서버가 있으므로 404는 고장): 404를 "집계 준비 중"이 아니라 오류("불러오지 못했어요")로 본다.
  it('no fabricated numbers: KPI API 404 (the server exists — a fault) shows the error state, never "집계 준비 중"; 지급 예정액 shows "정산 기능 준비 전"; settlement flow steps are all "준비 전"', async () => {
    renderHome(['VIEWER'], fakeApi({ transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(await within(stat('오늘 순승인액')).findByText('불러오지 못했어요')).toBeInTheDocument()
    expect(within(stat('오늘 승인 건수')).getByText('불러오지 못했어요')).toBeInTheDocument()
    expect(document.body).not.toHaveTextContent('집계 준비 중')
    expect(stat('오늘 순승인액')).not.toHaveTextContent(/\d/)
    expect(stat('오늘 승인 건수')).not.toHaveTextContent(/\d/)
    expect(within(stat('지급 예정액')).getByText('정산 기능 준비 전')).toBeInTheDocument()
    expect(stat('지급 예정액')).not.toHaveTextContent(/\d/)
    const flow = screen.getByRole('region', { name: '오늘의 정산 흐름' })
    expect(within(flow).getAllByText('준비 전')).toHaveLength(4)
    expect(Array.from(flow.querySelectorAll('.pipe b')).map((b) => b.textContent)).toEqual(['정산 데이터 생성', '지급 데이터 검증', '지급 승인', '송금 및 결과 확인'])
    expect(flow).toHaveTextContent('정산·지급 기능은 아직 연결 전이에요.')
    expect(flow).not.toHaveTextContent(/\d{2}:\d{2}|\d+건/)
    // 시안의 비교 %·샘플 문구는 없다.
    expect(document.body).not.toHaveTextContent(/이전 영업일 대비|샘플|128,400,000|3,125/)
  })

  // 기대값 변경(Coordinator Q1): 501도 "집계 준비 중"이 아니라 오류. 시험 이름은 본문(501 한 경우)에 맞췄다(리뷰 NIT — 다른 실패·세션 종료는 다음 시험).
  it('501 is an error too (no "집계 준비 중", no number)', async () => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => { throw new ApiError(501, 'RETRY_LATER') }), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(await within(stat('오늘 순승인액')).findByText('불러오지 못했어요')).toBeInTheDocument()
    expect(stat('오늘 순승인액')).not.toHaveTextContent(/\d/)
    expect(document.body).not.toHaveTextContent('집계 준비 중')
  })

  it('other KPI failures show an error without a number', async () => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(await within(stat('오늘 순승인액')).findByText('불러오지 못했어요')).toBeInTheDocument()
    expect(stat('오늘 순승인액')).toHaveTextContent(/권한/)
    expect(stat('오늘 승인 건수')).not.toHaveTextContent(/\d/)
    const ended = renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    await vi.waitFor(() => expect(ended.onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })

  // 기대값 변경(ADMIN-HOME-KPI-001 §4.4 — 잠정 배열 모양 → 명세 문서 모양): 행마다 asOf → 최상위 asOf, netApprovedAmount(major unit '-12.50') → netApprovedAmountMinor
  // (minor unit 정수 문자열 '-1250', §4.4 NIT), environment 필수. 건수는 환경 간 합산 금지(C-1)·통화별 분리(D-CP-002)라 '13건 · 통화 합계' 단언을 행별 건수로 바꾼다.
  it('T-K4-1 multi-row: grouped by environment (LIVE first, environment label on every group), one line per currency; counts per row, never summed across environments or currencies', async () => {
    const doc = kpiDoc([
      kpiItem('LIVE', 'KRW', '1234500', '0', '1234500', 12),
      kpiItem('LIVE', 'USD', '0', '1250', '-1250', 0),
      kpiItem('TEST', 'KRW', '30000', '0', '30000', 3),
    ])
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => doc), transactionSearch: noRecent() }))
    const amount = stat('오늘 순승인액')
    await vi.waitFor(() => expect(amount).toHaveTextContent('1,234,500원'))
    expect(within(amount).getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual(['LIVE 환경', 'TEST 환경'])
    const live = within(amount).getByRole('group', { name: 'LIVE 환경' })
    expect(within(live).getByText('LIVE')).toBeInTheDocument()
    expect(Array.from(live.querySelectorAll('strong')).map((s) => s.textContent)).toEqual(['1,234,500원', '-1,250USD(최소 단위)'])
    const test = within(amount).getByRole('group', { name: 'TEST 환경' })
    expect(within(test).getByText('TEST')).toBeInTheDocument()
    expect(Array.from(test.querySelectorAll('strong')).map((s) => s.textContent)).toEqual(['30,000원'])
    // T-K4-7: 표지·asOf.
    expect(amount).toHaveTextContent('PG 확정 시각 기준 · 정산 금액 아님')
    expect(amount).toHaveTextContent('10:30 기준')

    const count = stat('오늘 승인 건수')
    expect(within(count).getAllByRole('group').map((g) => g.getAttribute('aria-label'))).toEqual(['LIVE 환경', 'TEST 환경'])
    expect(Array.from(within(count).getByRole('group', { name: 'LIVE 환경' }).querySelectorAll('strong')).map((s) => s.textContent)).toEqual(['12건 · KRW', '0건 · USD'])
    expect(Array.from(within(count).getByRole('group', { name: 'TEST 환경' }).querySelectorAll('strong')).map((s) => s.textContent)).toEqual(['3건 · KRW'])
    expect(count).not.toHaveTextContent('15건')
    expect(count).not.toHaveTextContent('통화 합계')
  })

  it('T-K4-2 negative net (cancel-only row: an earlier approval cancelled today) is shown with its sign and count 0', async () => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => kpiDoc([kpiItem('LIVE', 'KRW', '150000', '170000', '-20000', 2)])), transactionSearch: noRecent() }))
    const amount = stat('오늘 순승인액')
    await vi.waitFor(() => expect(amount).toHaveTextContent('-20,000원'))
    expect(stat('오늘 승인 건수')).toHaveTextContent('2건 · KRW')
  })

  it('T-K4-3 amounts beyond 2^53 are shown digit-exact (no float conversion)', async () => {
    renderHome(['VIEWER'], fakeApi({
      getHomeKpis: vi.fn(async () => kpiDoc([
        kpiItem('LIVE', 'KRW', '9007199254740993', '0', '9007199254740993', 1),
        kpiItem('TEST', 'KRW', '9999999999999999999999999999999999999999', '1', '9999999999999999999999999999999999999998', 2),
      ])),
      transactionSearch: noRecent(),
    }))
    const amount = stat('오늘 순승인액')
    await vi.waitFor(() => expect(amount).toHaveTextContent('9,007,199,254,740,993원'))
    expect(amount).not.toHaveTextContent('9,007,199,254,740,992')
    expect(amount).toHaveTextContent('9,999,999,999,999,999,999,999,999,999,999,999,999,998원')
  })

  it('T-K4-4 empty list (no facts today): "오늘 완료된 승인 없음" with the label and asOf, no amount or count number', async () => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => kpiDoc([])), transactionSearch: noRecent() }))
    const amount = stat('오늘 순승인액')
    expect(await within(amount).findByText('오늘 완료된 승인 없음')).toBeInTheDocument()
    expect(amount.querySelector('strong')).not.toHaveTextContent(/\d/)
    expect(amount).toHaveTextContent('PG 확정 시각 기준 · 정산 금액 아님')
    expect(amount).toHaveTextContent('10:30 기준')
    const count = stat('오늘 승인 건수')
    expect(within(count).getByText('오늘 완료된 승인 없음')).toBeInTheDocument()
    expect(count).not.toHaveTextContent(/\d/)
  })

  // 기대값 변경(ADMIN-HOME-KPI-001 §4.3 fail-closed): 잘못된 금액 하나만 "표시할 수 없는 금액"으로 두던 것을 문서 전체 거부(오류 표시, 숫자 없음)로 바꾼다 —
  // ADMIN 서버가 같은 규칙으로 이미 503을 내므로 화면에 이런 문서가 오면 모양 계약 위반이다.
  const ok = kpiItem('LIVE', 'KRW', '1250', '0', '1250', 1)
  it.each<[string, unknown]>([
    ['exponent amount', kpiDoc([{ ...ok, netApprovedAmountMinor: '1e9', approvedAmountMinor: '1e9' }])],
    ['major-unit decimal amount', kpiDoc([{ ...ok, netApprovedAmountMinor: '12.50', approvedAmountMinor: '12.50' }])],
    ['negative zero', kpiDoc([kpiItem('LIVE', 'KRW', '0', '1', '-0', 0)])],
    ['leading zero', kpiDoc([{ ...ok, netApprovedAmountMinor: '01250', approvedAmountMinor: '01250' }])],
    ['amount as a JSON number', kpiDoc([{ ...ok, netApprovedAmountMinor: 1250 as unknown as string }])],
    ['net != approved - cancelled', kpiDoc([kpiItem('LIVE', 'KRW', '1250', '250', '1250', 1)])],
    ['negative approved', kpiDoc([kpiItem('LIVE', 'KRW', '-1250', '0', '-1250', 0)])],
    ['negative count', kpiDoc([{ ...ok, approvedCount: -1 }])],
    ['fractional count', kpiDoc([{ ...ok, approvedCount: 1.5 }])],
    ['count without approval', kpiDoc([kpiItem('LIVE', 'KRW', '0', '1250', '-1250', 1)])],
    ['row without facts', kpiDoc([kpiItem('LIVE', 'KRW', '0', '0', '0', 0)])],
    ['unknown environment', kpiDoc([{ ...ok, environment: 'PROD' }])],
    ['lowercase currency', kpiDoc([{ ...ok, currency: 'krw' }])],
    ['duplicate (environment, currency)', kpiDoc([ok, ok])],
    ['other time zone', { ...kpiDoc([ok]), timeZone: 'UTC' }],
    ['items not a list', { ...kpiDoc([]), items: { 0: ok } }],
    ['unparseable asOf', { ...kpiDoc([ok]), asOf: 'today' }],
    // 리뷰 MINOR-5(서버 PaymentResponseShape.HOME_KPI·HomeKpiAnswer와 같은 규칙 — §4.3): 정확한 키 집합, 엄격한 ISO 순간, 이 요청의 답.
    ['extra document key', { ...kpiDoc([ok]), total: '1250' }],
    ['missing document key', (({ from: _from, ...rest }) => rest)(kpiDoc([ok]))],
    ['extra item key', kpiDoc([{ ...ok, merchantId: 'mer_1' } as HomeKpiItem])],
    ['missing item key', kpiDoc([(({ cancelledAmountMinor: _c, ...rest }) => rest)(ok) as HomeKpiItem])],
    ['asOf without milliseconds', { ...kpiDoc([ok]), asOf: '2026-11-15T01:30:00Z' }],
    ['asOf with an offset instead of Z', { ...kpiDoc([ok]), asOf: '2026-11-15T10:30:00.000+09:00' }],
    ['asOf date-only (Date.parse accepts it)', { ...kpiDoc([ok]), asOf: '2026-11-15' }],
    ['impossible calendar asOf', { ...kpiDoc([ok]), asOf: '2026-02-30T01:30:00.000Z', businessDate: '2026-02-30', from: '2026-02-29T15:00:00.000Z' }],
    ['businessDate not the Seoul date of asOf', { ...kpiDoc([ok]), businessDate: '2026-11-14', from: '2026-11-13T15:00:00.000Z' }],
    ['businessDate not a strict date', { ...kpiDoc([ok]), businessDate: '2026-11-15T00:00:00Z' }],
    ['from not 00:00 Seoul of businessDate (UTC midnight)', { ...kpiDoc([ok]), from: '2026-11-15T00:00:00.000Z' }],
    ['from after asOf (also not 00:00 of that Seoul day)', { ...kpiDoc([ok]), asOf: '2026-11-14T14:59:59.999Z', businessDate: '2026-11-14', from: '2026-11-14T15:00:00.000Z' }],
    ['asOf = from with items', { ...kpiDoc([ok]), asOf: '2026-11-14T15:00:00.000Z' }],
  ])('malformed document (%s) is rejected: error shown, never a number', async (_name, doc) => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => doc as HomeKpis), transactionSearch: noRecent() }))
    const amount = stat('오늘 순승인액')
    expect(await within(amount).findByText('불러오지 못했어요')).toBeInTheDocument()
    expect(amount).not.toHaveTextContent(/\d/)
    expect(stat('오늘 승인 건수')).not.toHaveTextContent(/\d/)
  })

  it.each<[string, HomeKpis, string]>([
    ['asOf exactly 00:00 Seoul with no items (§3.1 empty interval)', { ...kpiDoc([]), asOf: '2026-11-14T15:00:00.000Z' }, '오늘 완료된 승인 없음'],
    ['asOf 23:59:59.999 Seoul is still the same business date', { ...kpiDoc([kpiItem('LIVE', 'KRW', '1250', '0', '1250', 1)]), asOf: '2026-11-15T14:59:59.999Z' }, '1,250원'],
  ])('boundary document accepted: %s', async (_name, doc, shown) => {
    renderHome(['VIEWER'], fakeApi({ getHomeKpis: vi.fn(async () => doc), transactionSearch: noRecent() }))
    await vi.waitFor(() => expect(stat('오늘 순승인액')).toHaveTextContent(shown))
    expect(stat('오늘 순승인액')).not.toHaveTextContent('불러오지 못했어요')
  })

  it('APPROVER: pending approvals (inbox call) and open findings; never calls "mine"; banner and todo rows from real items; no internal key of the target', async () => {
    const MER = 'mer_00000000000000000000000001'
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING', MER), approval('apr_2', 'PENDING')] })),
      reconciliation: vi.fn(async () => overview(3)),
      transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
    })
    const { onOpen } = renderHome(['APPROVER'], api)
    const work = stat('확인이 필요한 업무')
    await vi.waitFor(() => expect(work).toHaveTextContent('5건'))
    expect(work).toHaveTextContent('승인 2 · 대사 3')
    expect(api.approvals).toHaveBeenCalledTimes(1)
    expect(api.approvals).toHaveBeenCalledWith('pending')
    expect(screen.getByText('승인을 기다리는 요청이 2건 있어요')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /승인함 열기/ }))
    expect(onOpen).toHaveBeenCalledWith('approvals')
    const todo = screen.getByRole('region', { name: '지금 처리할 업무' })
    expect(within(todo).getAllByRole('button', { name: /역할 부여 승인 요청/ })).toHaveLength(2)
    expect(within(todo).getByText('5')).toHaveClass('count')
    // 리뷰 NIT: button 안에는 phrasing content만(div 없음).
    for (const button of within(todo).getAllByRole('button')) expect(button.querySelector('div')).toBeNull()
    await userEvent.click(within(todo).getByRole('button', { name: /맞지 않는 대사 발견/ }))
    expect(onOpen).toHaveBeenCalledWith('reconciliation')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('OPERATOR: counts only PENDING among my requests; no inbox and no findings call; no banner for own requests', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING'), approval('apr_2', 'APPROVED'), approval('apr_3', 'WITHDRAWN')] })),
      transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
    })
    renderHome(['OPERATOR'], api)
    await vi.waitFor(() => expect(stat('확인이 필요한 업무')).toHaveTextContent('1건'))
    expect(stat('확인이 필요한 업무')).toHaveTextContent('내 요청 1')
    expect(api.approvals).toHaveBeenCalledTimes(1)
    expect(api.approvals).toHaveBeenCalledWith('mine')
    expect(api.reconciliation).not.toHaveBeenCalled()
    expect(within(screen.getByRole('region', { name: '지금 처리할 업무' })).getByRole('button', { name: /내 요청이 승인을 기다리고/ })).toBeInTheDocument()
    expect(document.querySelector('.banner')).toBeNull()
  })

  it('SECURITY_ADMIN: my requests and findings (not the inbox); nothing to do shows the empty line and no banner', async () => {
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [] })), reconciliation: vi.fn(async () => overview(0)), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) })
    renderHome(['SECURITY_ADMIN'], api)
    await vi.waitFor(() => expect(stat('확인이 필요한 업무')).toHaveTextContent('0건'))
    expect(stat('확인이 필요한 업무')).toHaveTextContent('내 요청 0 · 대사 0')
    expect(api.approvals).toHaveBeenCalledWith('mine')
    expect(api.approvals).not.toHaveBeenCalledWith('pending')
    expect(await within(screen.getByRole('region', { name: '지금 처리할 업무' })).findByText('지금 처리할 업무가 없어요.')).toBeInTheDocument()
    expect(document.querySelector('.banner')).toBeNull()
    expect(screen.queryByRole('button', { name: '＋ 사업자 등록' })).toBeNull()
  })

  // T-E5-7(VIEWER 쪽): VIEWER는 최근 거래(가맹점·결제수단 칸 포함)를 받는 역할 — 승인·대사 호출은 없다. 라벨 칸 표시 자체는 아래 T-E5-1·T-E5-7 시험.
  it('T-E5-7 VIEWER: no approval/findings call; work card says none for this role; quick links only to menus it can use', async () => {
    const { api, onOpen } = renderHome(['VIEWER'], fakeApi({ transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(within(stat('확인이 필요한 업무')).getByText('해당 없음')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: '지금 처리할 업무' })).getByText('이 역할에는 처리할 업무 목록이 없어요.')).toBeInTheDocument()
    const quick = screen.getByRole('group', { name: '바로가기' })
    expect(within(quick).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['가맹점 조회 열기', '거래 조회 열기', '매입·대사 확인 열기', '계정·권한 관리 열기'])
    await userEvent.click(within(quick).getByRole('button', { name: '매입·대사 확인 열기' }))
    expect(onOpen).toHaveBeenCalledWith('acquisitions')
    expect(api.approvals).not.toHaveBeenCalled()
    expect(api.reconciliation).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '＋ 사업자 등록' })).toBeNull()
  })

  // T-E5-7(UNMASK 쪽): UNMASK만 가진 계정은 거래 조회 권한이 없어 최근 거래 검색을 부르지 않는다 — 가맹점명·결제수단이 화면에 나타나지 않는다.
  it('T-E5-7 UNMASK only: no payment data call and no KPI/work/recent section (no merchant label or payment method shown) — a single quick link to 계정·권한 (audit tab)', async () => {
    const { api, onOpen } = renderHome(['UNMASK'])
    expect(screen.queryAllByRole('article')).toHaveLength(0)
    expect(screen.queryByRole('region', { name: '최근 거래' })).toBeNull()
    expect(api.getHomeKpis).not.toHaveBeenCalled()
    expect(api.transactionSearch).not.toHaveBeenCalled()
    const quick = screen.getByRole('group', { name: '바로가기' })
    expect(within(quick).getAllByRole('button').map((b) => b.getAttribute('aria-label'))).toEqual(['계정·권한 관리 열기'])
    await userEvent.click(within(quick).getByRole('button'))
    expect(onOpen).toHaveBeenCalledWith('audit')
  })

  it('a list cut at the server limit (200 rows, no total) is shown as "200+", not as an exact count', async () => {
    const many = Array.from({ length: 200 }, (_, i) => approval(`apr_${i}`, 'PENDING'))
    renderHome(['APPROVER'], fakeApi({ approvals: vi.fn(async () => ({ approvals: many })), reconciliation: vi.fn(async () => overview(0)), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    await vi.waitFor(() => expect(stat('확인이 필요한 업무')).toHaveTextContent('200+건'))
    expect(screen.getByText('승인을 기다리는 요청이 200건 이상 있어요')).toBeInTheDocument()
  })

  // 기대값 변경(리뷰 MINOR-4: 실패 소스가 있으면 합계 숨김): 실패한 출처가 하나라도 있으면 합계 숫자("1건")와 지금 처리할 업무 count 배지를 보이지 않는다 —
  // 정확한 합계처럼 보이지 않게. 출처별 내역(승인 1 · 대사 오류)과 성공한 출처의 행은 그대로.
  it('a failed source shows the fixed error (no number for it) and hides the total and the count badge; a session-ending answer goes back to login', async () => {
    renderHome(['APPROVER'], fakeApi({
      approvals: vi.fn(async () => ({ approvals: [approval('apr_1', 'PENDING')] })),
      reconciliation: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }),
      transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
    }))
    const work = stat('확인이 필요한 업무')
    expect(await within(work).findByText(/대사: .*권한/)).toBeInTheDocument()
    expect(work).toHaveTextContent('대사 오류')
    expect(work).toHaveTextContent('승인 1')
    expect(within(work).getByText('일부 불러오지 못함')).toHaveClass('pending')
    expect(work.querySelector('strong')).not.toHaveTextContent(/\d/)
    expect(work).not.toHaveTextContent(/\d+\+?건/)
    const todo = screen.getByRole('region', { name: '지금 처리할 업무' })
    expect(within(todo).getByRole('button', { name: /역할 부여 승인 요청/ })).toBeInTheDocument()
    expect(todo.querySelector('.count')).toBeNull()

    const ended = renderHome(['OPERATOR'], fakeApi({ approvals: vi.fn(async () => { throw apiError(401, 'SESSION_EXPIRED') }), transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    await vi.waitFor(() => expect(ended.onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
  })

  it('recent payments: existing search for the last 7 days (KST), first 5 rows, link to 거래 조회; empty and error states', async () => {
    const items = Array.from({ length: 7 }, (_, i) => txn(`${PAY.slice(0, -1)}${i}`))
    const api = fakeApi({ transactionSearch: vi.fn(async () => ({ items, nextCursor: null, merchantLabels: 'OK' as const })) })
    const { onOpen } = renderHome(['VIEWER'], api)
    const recent = screen.getByRole('region', { name: '최근 거래' })
    expect(await within(recent).findAllByRole('row', { name: /^pay_/ })).toHaveLength(5)
    // 기대값 변경(TXN-LIST-ENRICH-001 M-6 / T-E5-8): 운영 홈은 5건만 쓰므로 limit 5를 보낸다(전에는 limit 없이 서버 기본 20건을 받아 앞 5건만 표시).
    expect(api.transactionSearch).toHaveBeenCalledWith({ createdFrom: '2026-11-08T15:00:00.000Z', createdTo: '2026-11-15T15:00:00.000Z', limit: 5 })
    const row = within(recent).getAllByRole('row', { name: /^pay_/ })[0]!
    expect(row.querySelector('strong')).toHaveTextContent('데모 주문')
    expect(row.querySelector('.tag.green')).toHaveTextContent('승인')
    expect(row.querySelector('td.amount')).toHaveTextContent('12,000원')
    expect(recent).toHaveTextContent('최근 5건')
    await userEvent.click(within(recent).getByRole('button', { name: '전체 거래 보기 →' }))
    expect(onOpen).toHaveBeenCalledWith('transactions')
  })

  // T-E5-7(VIEWER 표시): 조회 전용 VIEWER에게도 가맹점 라벨·결제수단이 보인다.
  it('T-E5-1·T-E5-7 (VIEWER) recent payments show "<상호> · <가맹점 코드>" with "환경 · MID" below, and the payment method label "카드"; columns in order', async () => {
    renderHome(['VIEWER'], fakeApi({ transactionSearch: vi.fn(async () => ({ items: [txn(PAY)], nextCursor: null, merchantLabels: 'OK' as const })) }))
    const recent = screen.getByRole('region', { name: '최근 거래' })
    const row = (await within(recent).findAllByRole('row', { name: /^pay_/ }))[0]!
    expect(within(recent).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['주문 / 결제 ID', '가맹점', '결제수단', '거래일시', '상태', '금액'])
    const cells = within(row).getAllByRole('cell')
    expect(cells[1]!.querySelector('strong')).toHaveTextContent('데모상점 · demoshop01')
    expect(cells[1]!.querySelector('small')).toHaveTextContent(`LIVE · ${MID}`)
    expect(cells[2]).toHaveTextContent('카드')
    expect(recent).not.toHaveTextContent('가맹점 정보를 불러오지 못했어요')
  })

  it('T-E5-2 a MID without a label shows "가맹점 정보 없음" + MID; UNAVAILABLE labels show the MID only and one note under the table', async () => {
    const { unmount } = render(<HomeScreen api={fakeApi({ transactionSearch: vi.fn(async () => ({ items: [txn(PAY, 'AUTHORIZED', null)], nextCursor: null, merchantLabels: 'OK' as const })) })} me={me(['VIEWER'])} menus={menusFor(['VIEWER'])} onOpen={vi.fn()} onSessionEnded={vi.fn()} now={() => NOW} />)
    let row = (await within(screen.getByRole('region', { name: '최근 거래' })).findAllByRole('row', { name: /^pay_/ }))[0]!
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent(`가맹점 정보 없음LIVE · ${MID}`)
    unmount()
    renderHome(['VIEWER'], fakeApi({ transactionSearch: vi.fn(async () => ({ items: [txn(PAY, 'AUTHORIZED', null)], nextCursor: null, merchantLabels: 'UNAVAILABLE' as const })) }))
    const recent = screen.getByRole('region', { name: '최근 거래' })
    row = (await within(recent).findAllByRole('row', { name: /^pay_/ }))[0]!
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent(`LIVE · ${MID}`)
    expect(within(row).getAllByRole('cell')[1]).not.toHaveTextContent('가맹점 정보 없음')
    expect(within(recent).getAllByText('가맹점 정보를 불러오지 못했어요')).toHaveLength(1)
  })

  it('T-E5-3·T-E5-5·T-E5-6 an unknown payment method is shown as its code; markup in a name is text; no internal key in the DOM', async () => {
    renderHome(['VIEWER'], fakeApi({
      transactionSearch: vi.fn(async () => ({ items: [txn(PAY, 'AUTHORIZED', { name: '<script>alert(1)</script>', code: 'demoshop01' }, 'BANK_TRANSFER')], nextCursor: null, merchantLabels: 'OK' as const })),
    }))
    const recent = screen.getByRole('region', { name: '최근 거래' })
    const row = (await within(recent).findAllByRole('row', { name: /^pay_/ }))[0]!
    expect(within(row).getAllByRole('cell')[2]).toHaveTextContent('BANK_TRANSFER')
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent('<script>alert(1)</script> · demoshop01')
    expect(document.querySelector('script')).toBeNull()
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('recent payments empty / failed', async () => {
    renderHome(['VIEWER'], fakeApi({ transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })) }))
    expect(await screen.findByText('최근 7일 동안의 거래가 없어요.')).toBeInTheDocument()
  })
})
