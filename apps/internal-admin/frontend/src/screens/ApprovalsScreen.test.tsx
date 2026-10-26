import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { ApprovalView } from '../api/adminApi'

const pending: ApprovalView = {
  approvalId: 'apr_1', kind: 'PASSWORD_RESET', targetActorId: 'adm_T', targetSnapshot: '{}', grantedRole: null, causeApprovalId: null,
  requestedBy: 'adm_R', requestedByDisplayName: '요청자', requestedAt: '2026-10-26T00:00:00Z', expiresAt: '2026-10-27T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
}

describe('ApprovalsScreen (inbox — approve/reject with TOTP)', () => {
  it('requires a 6-digit TOTP before approving; a reset approval shows its code once, then drops it', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [pending] })),
      approve: vi.fn(async () => ({ approvalId: 'apr_1', status: 'EXECUTED', enrollmentCode: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ234567' })),
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide />)
    const row = await screen.findByRole('row', { name: /PASSWORD_RESET/ })
    const approve = within(row).getByRole('button', { name: '승인' })
    expect(approve).toBeDisabled()
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '12345')
    expect(approve).toBeDisabled()
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '6')
    await userEvent.click(approve)
    expect(api.approve).toHaveBeenCalledWith('apr_1', '123456')
    expect(await screen.findByRole('dialog')).toHaveTextContent('ABCD EFGH')
    await userEvent.click(screen.getByRole('button', { name: '확인했습니다 — 닫기' }))
    expect(screen.queryByText(/ABCD EFGH/)).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  it('maps CREDENTIAL_ISSUER and STEP_UP_FAILED to fixed explanations and keeps the request listed', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [pending] })),
      approve: vi.fn(async () => { throw apiError(403, 'CREDENTIAL_ISSUER') }),
      reject: vi.fn(async () => { throw apiError(403, 'STEP_UP_FAILED') }),
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide />)
    const row = await screen.findByRole('row', { name: /PASSWORD_RESET/ })
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이 계정의 자격을 발급한 관리자')
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '654321')
    await userEvent.click(within(row).getByRole('button', { name: '거절' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('인증 코드가 올바르지 않습니다')
    expect(screen.getByRole('row', { name: /PASSWORD_RESET/ })).toBeInTheDocument()
  })

  it('"my requests" offers withdraw (no TOTP) instead of decisions', async () => {
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [pending] })), withdraw: vi.fn(async () => ({ status: 'WITHDRAWN' })) })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} />)
    const row = await screen.findByRole('row', { name: /PASSWORD_RESET/ })
    expect(within(row).queryByRole('button', { name: '승인' })).toBeNull()
    await userEvent.click(within(row).getByRole('button', { name: '철회' }))
    expect(api.withdraw).toHaveBeenCalledWith('apr_1')
    expect(api.approvals).toHaveBeenCalledWith('mine')
  })
})
