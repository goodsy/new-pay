import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { TransactionsScreen } from './TransactionsScreen'
import type { TransactionDetail, TransactionListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/** Demo 식별자(PAYMENT 형식 — 접두사 + Crockford base32 26자). 업무 의미 없는 고정값. */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const PAY1 = id('pay', 1)
const PAY2 = id('pay', 2)
/** 시험 시각: 2026-11-04 10:00 KST(= 01:00 UTC). 기본 기간은 KST 10-29 ~ 11-04(7일). */
const NOW = Date.parse('2026-11-04T01:00:00.000Z')
const FULL_NAME = '가나다라마바사아자차카타파하'

const item = (paymentId: string, preview: string | null = '가나다라마바사아자차', truncated = true): TransactionListItem => ({
  paymentId, environment: 'TEST', merchantAccountId: 'mid-demo-1', merchantOrderId: 'order-77', orderNamePreview: preview, orderNameTruncated: truncated,
  amountMinor: 10000, currency: 'KRW', status: 'AUTHORIZED', createdAt: '2026-11-01T01:02:03.456Z',
})

/** 상세 한 건(TXN-OPS-001 §3.4 — 승인 1, 거래 사실 2(승인·부분 취소), 취소 2(운영자 완료·가맹점 실패), 잔액, hold 있음). 금액은 서로 맞다(10,000 − 3,000). */
const detail: TransactionDetail = {
  payment: {
    paymentId: PAY1, environment: 'TEST', merchantAccountId: 'mid-demo-1', merchantSiteId: 'demo-site-001', merchantOrderId: 'order-77', orderName: FULL_NAME,
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
    {
      transactionId: id('ptx', 2), type: 'CANCEL', amountMinor: 3000, currency: 'KRW', planKind: null, installmentMonths: null, cardType: null,
      approvalNumber: null, providerTransactionId: 'SIMPTX-000112', cancellationId: id('pcn', 1), originalTransactionId: id('ptx', 1), cancelScope: 'PARTIAL',
      institutionTransactionDate: '2026-11-01', institutionBusinessDate: '2026-11-02', confirmedAt: '2026-11-01T02:00:00.000Z', recordedBy: 'RESOLUTION',
    },
  ],
  cancellations: [
    { cancellationId: id('pcn', 1), initiatorKind: 'OPERATOR', merchantCancelId: null, scope: 'PARTIAL', status: 'COMPLETED', failureEvidence: null, amountMinor: 3000, currency: 'KRW', openedAt: '2026-11-01T01:59:00.000Z' },
    { cancellationId: id('pcn', 2), initiatorKind: 'MERCHANT', merchantCancelId: 'mc-1', scope: 'PARTIAL', status: 'FAILED', failureEvidence: 'INSTITUTION_CONFIRMED_DECLINE', amountMinor: 2000, currency: 'KRW', openedAt: '2026-11-01T03:00:00.000Z' },
  ],
  returnBalance: { currency: 'KRW', authorizedMinor: 10000, completedMinor: 3000, reservedMinor: 0, forcedMinor: 0, revision: 3 },
}

const search = async () => userEvent.click(screen.getByRole('button', { name: '검색' }))
const choose = async (label: string, value: string) => userEvent.selectOptions(screen.getByLabelText(label), value)

describe('TransactionsScreen (TXN-OPS-001 §7 AC 10 — read-only transaction lookup)', () => {
  it('a period search sends KST calendar days as UTC instants [from, next day) with the filters, lists 10-character names and pages with the cursor', async () => {
    const transactionSearch = vi.fn()
      .mockResolvedValueOnce({ items: [item(PAY1)], nextCursor: { createdAt: '2026-11-01T01:02:03.456Z', paymentId: PAY1 } })
      .mockResolvedValueOnce({ items: [item(PAY2, '짧은 이름', false)], nextCursor: null })
    const api = fakeApi({ transactionSearch })
    render(<TransactionsScreen api={api} now={() => NOW} />)
    expect(screen.getByLabelText('시작일(KST)')).toHaveValue('2026-10-29')
    expect(screen.getByLabelText('종료일(KST)')).toHaveValue('2026-11-04')
    await choose('환경', 'LIVE')
    await userEvent.type(screen.getByLabelText('MID'), '  mid-demo-1 ')
    await choose('결제 상태', 'AUTHORIZED')
    await search()
    expect(transactionSearch).toHaveBeenLastCalledWith({
      createdFrom: '2026-10-28T15:00:00.000Z', createdTo: '2026-11-04T15:00:00.000Z', environment: 'LIVE', merchantAccountId: 'mid-demo-1', status: 'AUTHORIZED',
    })
    const row = await screen.findByRole('row', { name: PAY1 })
    expect(within(row).getByText('가나다라마바사아자차…')).toBeInTheDocument()
    expect(within(row).getByText('10,000원')).toBeInTheDocument()
    expect(screen.queryByText(FULL_NAME)).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '다음' }))
    expect(transactionSearch).toHaveBeenLastCalledWith({
      createdFrom: '2026-10-28T15:00:00.000Z', createdTo: '2026-11-04T15:00:00.000Z', environment: 'LIVE', merchantAccountId: 'mid-demo-1', status: 'AUTHORIZED',
      after: { createdAt: '2026-11-01T01:02:03.456Z', paymentId: PAY1 },
    })
    expect(within(await screen.findByRole('row', { name: PAY2 })).getByText('짧은 이름')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  it('more than 31 days or an inverted period is refused on screen without a call (the server checks again)', async () => {
    const api = fakeApi({ transactionSearch: vi.fn() })
    render(<TransactionsScreen api={api} now={() => NOW} />)
    const from = screen.getByLabelText('시작일(KST)')
    await userEvent.clear(from)
    await userEvent.type(from, '2026-10-04')
    await search()
    expect(screen.getByRole('alert')).toHaveTextContent('조회 기간은 최대 31일입니다.')
    await userEvent.clear(from)
    await userEvent.type(from, '2026-10-05')
    await userEvent.clear(screen.getByLabelText('종료일(KST)'))
    await userEvent.type(screen.getByLabelText('종료일(KST)'), '2026-10-04')
    await search()
    expect(screen.getByRole('alert')).toHaveTextContent('시작일이 종료일보다 늦습니다.')
    expect(api.transactionSearch).not.toHaveBeenCalled()
    // 31일(10-05 ~ 11-04)은 보낸다.
    await userEvent.clear(screen.getByLabelText('종료일(KST)'))
    await userEvent.type(screen.getByLabelText('종료일(KST)'), '2026-11-04')
    vi.mocked(api.transactionSearch).mockResolvedValue({ items: [], nextCursor: null })
    await search()
    expect(api.transactionSearch).toHaveBeenCalledWith({ createdFrom: '2026-10-04T15:00:00.000Z', createdTo: '2026-11-04T15:00:00.000Z' })
    expect(await screen.findByText('조건에 맞는 거래가 없습니다.')).toBeInTheDocument()
  })

  it('the paymentId lookup sends only the id; the order lookup needs MID and order number and sends no period', async () => {
    const transactionSearch = vi.fn(async () => ({ items: [item(PAY1)], nextCursor: null }))
    const api = fakeApi({ transactionSearch })
    render(<TransactionsScreen api={api} now={() => NOW} />)
    await choose('조회 방식', 'payment')
    await userEvent.type(screen.getByLabelText('결제 ID'), ` ${PAY1} `)
    await search()
    expect(transactionSearch).toHaveBeenLastCalledWith({ paymentId: PAY1 })
    await choose('조회 방식', 'order')
    await userEvent.type(screen.getByLabelText('가맹점 주문번호'), 'order-77')
    await search()
    expect(screen.getByRole('alert')).toHaveTextContent('MID와 가맹점 주문번호를 모두 입력해 주세요.')
    expect(transactionSearch).toHaveBeenCalledTimes(1)
    await userEvent.type(screen.getByLabelText('MID'), 'mid-demo-1')
    await choose('환경', 'TEST')
    await search()
    expect(transactionSearch).toHaveBeenLastCalledWith({ environment: 'TEST', merchantAccountId: 'mid-demo-1', merchantOrderId: 'order-77' })
  })

  it('the detail shows the full order name, approval number, provider transaction id, cancel initiators (no reason), the balance and the hold', async () => {
    const api = fakeApi({ transactionSearch: vi.fn(async () => ({ items: [item(PAY1)], nextCursor: null })), transactionDetail: vi.fn(async () => detail) })
    render(<TransactionsScreen api={api} now={() => NOW} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: PAY1 })).getByRole('button', { name: '상세' }))
    expect(api.transactionDetail).toHaveBeenCalledWith(PAY1)
    const payment = await screen.findByRole('region', { name: '결제' })
    expect(within(payment).getByText(FULL_NAME)).toBeInTheDocument()
    expect(within(payment).getByText('보류(hold) 중')).toBeInTheDocument()
    const facts = screen.getByRole('table', { name: '거래 사실 목록' })
    expect(within(within(facts).getByRole('row', { name: id('ptx', 1) })).getByText('12345678')).toBeInTheDocument()
    expect(within(facts).getByText('SIMPTX-000111')).toBeInTheDocument()
    expect(within(facts).getByText('취소 (부분)')).toBeInTheDocument()
    const cancels = screen.getByRole('table', { name: '취소 이력 목록' })
    expect(within(within(cancels).getByRole('row', { name: id('pcn', 1) })).getByText('운영자 보정')).toBeInTheDocument()
    expect(within(within(cancels).getByRole('row', { name: id('pcn', 2) })).getByText('가맹점 요청')).toBeInTheDocument()
    expect(within(cancels).getByText('INSTITUTION_CONFIRMED_DECLINE')).toBeInTheDocument()
    const balance = screen.getByRole('region', { name: '반환 잔액' })
    expect(within(balance).getByText('3,000원')).toBeInTheDocument()
    expect(screen.getByText('운영자 보정 취소의 사유·근거는 이 화면에 표시하지 않습니다(개시 주체만 표시).')).toBeInTheDocument()
  })

  it('an audit failure shows the fixed message and no detail; a read failure says the payment service is unreachable (not "not saved")', async () => {
    const transactionDetail = vi.fn().mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE')).mockRejectedValueOnce(apiError(503, 'PAYMENT_UNAVAILABLE'))
    const api = fakeApi({ transactionSearch: vi.fn(async () => ({ items: [item(PAY1)], nextCursor: null })), transactionDetail })
    render(<TransactionsScreen api={api} now={() => NOW} />)
    await search()
    const details = () => within(screen.getByRole('row', { name: PAY1 })).getByRole('button', { name: '상세' })
    await userEvent.click(await screen.findByRole('button', { name: '상세' }))
    expect(await screen.findByText('감사 기록을 남길 수 없어 조회하지 않았습니다. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '결제' })).toBeNull()
    await userEvent.click(details())
    expect(await screen.findByText('결제 서비스에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
  })

  it('a session that ended returns to login through onSessionEnded; a rate limit shows the fixed message', async () => {
    const onSessionEnded = vi.fn()
    const transactionSearch = vi.fn().mockRejectedValueOnce(apiError(429, 'RATE_LIMITED')).mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    render(<TransactionsScreen api={fakeApi({ transactionSearch })} onSessionEnded={onSessionEnded} now={() => NOW} />)
    await search()
    expect(await screen.findByText('조회가 너무 잦습니다. 잠시 후 다시 시도해 주세요.')).toBeInTheDocument()
    await search()
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
  })
})
