import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { fakeApi } from '../test/fakeApi'
import type { ApprovalView } from '../api/adminApi'

/** ADMIN-DEMO-LOGIN-001 결정 ③: 데모 간편 로그인 세션(me.demoQuickLogin)이면 TOTP 입력 대신 "데모: 재인증 생략" 안내, 빈 코드로 보낸다. 아니면 기존 그대로. */
const pending: ApprovalView = {
  approvalId: 'apr_1', kind: 'ROLE_GRANT', targetActorId: 'adm_T', targetSnapshot: '{}', grantedRole: 'VIEWER', causeApprovalId: null,
  requestedBy: 'adm_R', requestedByDisplayName: '요청자', requestedAt: '2026-10-26T00:00:00Z', expiresAt: '2026-10-27T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
}

describe('ApprovalsScreen — demo quick login step-up waiver', () => {
  it('a demo session sees the waiver note instead of the code field; approve and reject send an empty code', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [pending, { ...pending, approvalId: 'apr_2' }] })),
      approve: vi.fn(async () => ({ approvalId: 'apr_1', status: 'EXECUTED' })),
      reject: vi.fn(async () => ({ status: 'REJECTED' })),
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'], demoQuickLogin: true }} />)
    const rows = await screen.findAllByRole('row', { name: /ROLE_GRANT/ })
    const row = rows[0]!
    expect(within(row).getByRole('note', { name: '재인증 생략' })).toHaveTextContent('데모: 재인증 생략')
    expect(within(row).queryByLabelText('인증 코드(6자리)')).toBeNull()
    const approve = within(row).getByRole('button', { name: '승인' })
    expect(approve).toBeEnabled()
    await userEvent.click(approve)
    expect(api.approve).toHaveBeenCalledWith('apr_1', '')
    const second = (await screen.findAllByRole('row', { name: /ROLE_GRANT/ }))[1]!
    await userEvent.click(within(second).getByRole('button', { name: '거절' }))
    expect(api.reject).toHaveBeenCalledWith('apr_2', '')
  })

  it('a normal session (flag absent or false) keeps the 6-digit code field and the disabled buttons until it is filled', async () => {
    for (const viewer of [{ actorId: 'adm_A', roles: ['APPROVER'] }, { actorId: 'adm_A', roles: ['APPROVER'], demoQuickLogin: false }]) {
      const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [pending] })) })
      const { unmount } = render(<ApprovalsScreen api={api} view="pending" canDecide viewer={viewer} />)
      const row = await screen.findByRole('row', { name: /ROLE_GRANT/ })
      expect(within(row).getByLabelText('인증 코드(6자리)')).toBeInTheDocument()
      expect(within(row).queryByRole('note', { name: '재인증 생략' })).toBeNull()
      expect(within(row).getByRole('button', { name: '승인' })).toBeDisabled()
      unmount()
    }
  })
})
