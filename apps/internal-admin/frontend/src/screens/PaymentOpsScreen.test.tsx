import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PaymentOpsScreen } from './PaymentOpsScreen'
import { ApiError } from '../api/client'
import { fakeApi } from '../test/fakeApi'
import type { PaymentTargetView } from '../api/adminApi'

const PAY = 'pay_01JC0000000000000000000001'
const target: PaymentTargetView = {
  paymentId: PAY, paymentStatus: 'AUTHORIZED', cancellable: true, currency: 'KRW', authorizedMinor: 10000, cancellableMinor: 7000, balanceRevision: 3,
  onHold: false, inFlightCancel: false,
}

async function lookUp() {
  await userEvent.type(screen.getByLabelText('결제 ID'), PAY)
  await userEvent.click(screen.getByRole('button', { name: '대상 조회' }))
}

describe('PaymentOpsScreen (operator correction cancel request — §4.20)', () => {
  it('OPERATOR: the amount defaults to the full returnable amount; reference and reason are trimmed before sending', async () => {
    const api = fakeApi({
      paymentTarget: vi.fn(async () => target),
      requestOperatorCancel: vi.fn(async () => ({ approvalId: 'apr_NEW' })),
    })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    expect(await screen.findByText('반환 가능액: 7000 KRW')).toBeInTheDocument()
    expect(screen.getByLabelText('취소 금액(최소 단위)')).toHaveValue('7000')
    await userEvent.selectOptions(screen.getByLabelText('목적 종류'), 'MERCHANT_TERMINATED_OBLIGATION')
    await userEvent.type(screen.getByLabelText('근거 참조'), '  RECON-2026-0001  ')
    await userEvent.type(screen.getByLabelText('사유'), '  duplicate charge  ')
    await userEvent.click(screen.getByRole('button', { name: '승인 요청' }))
    expect(api.requestOperatorCancel).toHaveBeenCalledWith({
      paymentId: PAY, amountMinor: 7000, purposeKind: 'MERCHANT_TERMINATED_OBLIGATION', purposeReference: 'RECON-2026-0001', reason: 'duplicate charge',
    })
    expect(await screen.findByRole('status')).toHaveTextContent('승인 요청을 만들었습니다: apr_NEW')
  })

  it('a cancel already in progress disables the request with the reason', async () => {
    const api = fakeApi({ paymentTarget: vi.fn(async () => ({ ...target, inFlightCancel: true })) })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    expect(await screen.findByText('진행 중인 취소가 있어 지금은 요청할 수 없습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '승인 요청' })).toBeDisabled()
  })

  it('S8 review L-1 (D-CANCEL-001): a payment on hold can still be requested — merchant cancels are blocked, the operator correction cancel is the path', async () => {
    const api = fakeApi({ paymentTarget: vi.fn(async () => ({ ...target, onHold: true })), requestOperatorCancel: vi.fn(async () => ({ approvalId: 'apr_H' })) })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    expect(await screen.findByText('보류 중 — 가맹점 취소는 막혀 있고 운영자 보정 취소로만 처리합니다.')).toBeInTheDocument()
    await userEvent.type(screen.getByLabelText('근거 참조'), 'RECON-2026-0001')
    await userEvent.type(screen.getByLabelText('사유'), 'hold correction')
    expect(screen.getByRole('button', { name: '승인 요청' })).toBeEnabled()
  })

  it('S8 review L-2: an amount beyond the safe integer range is an input error (not sent)', async () => {
    const api = fakeApi({ paymentTarget: vi.fn(async () => target), requestOperatorCancel: vi.fn(async () => ({ approvalId: 'apr_X' })) })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    await screen.findByText('반환 가능액: 7000 KRW')
    await userEvent.clear(screen.getByLabelText('취소 금액(최소 단위)'))
    await userEvent.type(screen.getByLabelText('취소 금액(최소 단위)'), '9007199254740993')
    await userEvent.type(screen.getByLabelText('근거 참조'), 'RECON-2026-0001')
    await userEvent.type(screen.getByLabelText('사유'), 'too large')
    expect(screen.getByText('금액이 허용 범위를 넘습니다.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '승인 요청' })).toBeDisabled()
  })

  it('S8 review L-2: a returnable amount that is not a safe integer is a display error (no form, no default)', async () => {
    const api = fakeApi({ paymentTarget: vi.fn(async () => ({ ...target, cancellableMinor: 2 ** 53 })) })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    expect(await screen.findByRole('alert')).toHaveTextContent('금액을 정확히 표시할 수 없어')
    expect(screen.queryByRole('button', { name: '승인 요청' })).toBeNull()
  })

  it('other roles see the target but no request form', async () => {
    const api = fakeApi({ paymentTarget: vi.fn(async () => target) })
    render(<PaymentOpsScreen api={api} canRequest={false} />)
    await lookUp()
    expect(await screen.findByText('반환 가능액: 7000 KRW')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '승인 요청' })).toBeNull()
    expect(screen.queryByLabelText('사유')).toBeNull()
  })

  it('I-111: a refused field shows its fixed reason (date-like references explained) without echoing the value', async () => {
    const api = fakeApi({
      paymentTarget: vi.fn(async () => target),
      requestOperatorCancel: vi.fn(async () => { throw new ApiError(400, 'INVALID_INPUT', { field: 'purposeReference', reason: 'CARD_NUMBER_PATTERN' }) }),
    })
    render(<PaymentOpsScreen api={api} canRequest />)
    await lookUp()
    await screen.findByText('반환 가능액: 7000 KRW')
    await userEvent.type(screen.getByLabelText('근거 참조'), 'RECON-20261028-000001')
    await userEvent.type(screen.getByLabelText('사유'), 'duplicate')
    await userEvent.click(screen.getByRole('button', { name: '승인 요청' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('근거 참조')
    expect(alert).toHaveTextContent('카드 번호')
    expect(alert).toHaveTextContent('날짜·일련번호')
    expect(alert).not.toHaveTextContent('20261028')
  })
})
