import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from '../screens/ApprovalsScreen'
import { fakeApi } from '../test/fakeApi'
import type { ApprovalView, PaymentTargetView } from '../api/adminApi'

const PAY = 'pay_01JC0000000000000000000001'
const pinned = { paymentId: PAY, paymentStatus: 'AUTHORIZED', currency: 'KRW', authorizedMinor: 10000, cancellableMinor: 7000, balanceRevision: 3, onHold: false }
const current: PaymentTargetView = { ...pinned, cancellable: true, inFlightCancel: false }
const pending: ApprovalView = {
  approvalId: 'apr_P', kind: 'OPERATOR_CORRECTION_CANCEL', targetKind: 'PAYMENT', targetActorId: null, targetRef: PAY, targetSnapshot: '{}', grantedRole: null,
  causeApprovalId: null, requestedBy: 'adm_R', requestedByDisplayName: '운영자', requestedAt: '2026-10-28T00:00:00Z', expiresAt: '2026-10-29T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
  owner: {
    amountMinor: 7000, currency: 'KRW', purposeKind: 'INCIDENT_OR_RECON_CORRECTION', pinnedTarget: pinned, approvedAt: null, executeBefore: null, attemptCount: null,
    nextAttemptAt: null, lastResponseAt: null, lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
  },
}

async function open(target: () => Promise<PaymentTargetView>) {
  const api = fakeApi({
    approvals: vi.fn(async () => ({ approvals: [pending] })), approvalDetail: vi.fn(async () => pending), paymentTarget: vi.fn(target),
    approve: vi.fn(async () => ({ approvalId: 'apr_P', status: 'EXECUTING' })),
  })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
  const row = await screen.findByRole('row', { name: /PENDING/ })
  await userEvent.click(within(row).getByRole('button', { name: '상세' }))
  const panel = await screen.findByRole('region', { name: '소유 서비스 실행 상세' })
  return { api, row, panel }
}

describe('Owner approval detail — pinned (request time) vs current values (S8 review M-1, §4.20)', () => {
  it('matching values: both columns are shown side by side and there is no warning', async () => {
    const { api, panel } = await open(async () => current)
    const table = await within(panel).findByRole('table', { name: '고정 값과 현재 값' })
    expect(within(table).getByRole('row', { name: /revision 3 3/ })).toBeInTheDocument()
    expect(within(table).getByRole('row', { name: /반환 가능액 7000 7000/ })).toBeInTheDocument()
    expect(within(panel).queryByText(/승인 시 무효/)).toBeNull()
    expect(within(panel).queryByText(/TARGET_BUSY/)).toBeNull()
    expect(api.paymentTarget).toHaveBeenCalledWith(PAY)
  })

  it('a different revision warns that approving invalidates it and consumes the TOTP; the approve button stays enabled (the server decides)', async () => {
    const { row, panel } = await open(async () => ({ ...current, balanceRevision: 4 }))
    expect(await within(panel).findByText('승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다')).toBeInTheDocument()
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    expect(within(row).getByRole('button', { name: '승인' })).toBeEnabled()
  })

  it('an amount above the current returnable amount, or a non-cancellable payment, also warns', async () => {
    const lower = await open(async () => ({ ...current, cancellableMinor: 5000 }))
    expect(await within(lower.panel).findByText('승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다')).toBeInTheDocument()
  })

  it('a cancel in progress shows the TARGET_BUSY guidance', async () => {
    const { panel } = await open(async () => ({ ...current, inFlightCancel: true }))
    expect(await within(panel).findByText(/진행 중인 취소가 있어 지금 승인하면 TARGET_BUSY/)).toBeInTheDocument()
  })

  it('a failed current lookup says the current values cannot be checked', async () => {
    const { panel } = await open(async () => { throw new Error('down') })
    expect(await within(panel).findByText('현재 값 확인 불가')).toBeInTheDocument()
    expect(within(panel).queryByText(/승인 시 무효/)).toBeNull()
  })
})
