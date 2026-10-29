import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { ApprovalView, OwnerDetail } from '../api/adminApi'

const owner: OwnerDetail = {
  amountMinor: 7000, currency: 'KRW', purposeKind: 'INCIDENT_OR_RECON_CORRECTION', pinnedTarget: { paymentId: 'pay_1' }, approvedAt: '2026-10-28T00:00:00Z',
  executeBefore: '2026-10-29T00:00:00Z', attemptCount: 3, nextAttemptAt: '2026-10-28T00:10:00Z', lastResponseAt: null, lastFailure: 'HTTP_503', integrityFlag: null,
  resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
}
function item(status: string, o: Partial<OwnerDetail> = {}, requestedBy = 'adm_R'): ApprovalView {
  return {
    approvalId: `apr_${status}`, kind: 'OPERATOR_CORRECTION_CANCEL', targetKind: 'PAYMENT', targetActorId: null, targetRef: 'pay_1', targetSnapshot: '{}', grantedRole: null,
    causeApprovalId: null, requestedBy, requestedByDisplayName: '운영자', requestedAt: '2026-10-28T00:00:00Z', expiresAt: '2026-10-29T00:00:00Z', status,
    decidedBy: 'adm_A', decidedByDisplayName: '승인자', decidedAt: '2026-10-28T00:00:00Z', resultCode: status === 'OWNER_REJECTED' ? 'CANCEL_IN_PROGRESS' : null,
    owner: { ...owner, ...o },
  }
}
const approver = { actorId: 'adm_A', roles: ['APPROVER'] }

async function openDetail(view: ApprovalView, viewer = approver, extra: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), ...extra })
  render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={viewer} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  await userEvent.click(within(row).getByRole('button', { name: '상세' }))
  return { api, panel: await screen.findByRole('region', { name: '소유 서비스 실행 상세' }) }
}

describe('ApprovalsScreen — owner-service approvals (S8 §4.20, I-105)', () => {
  it('EXECUTION_UNKNOWN: automatic re-check message, the attempt count is "command attempt opportunities", and there is no manual close button', async () => {
    const { panel } = await openDetail(item('EXECUTION_UNKNOWN'))
    expect(panel).toHaveTextContent('결과 확인 중 — 자동으로 다시 확인합니다')
    expect(panel).toHaveTextContent('명령 시도 기회 수(결정 조회만 한 주기 포함): 3')
    expect(within(panel).queryByRole('button', { name: /종료|완료 처리|수동/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /수동 종료|강제 종료/ })).toBeNull()
  })

  it('a halted resend or an integrity flag says "investigation needed — no manual close"', async () => {
    const halted = await openDetail(item('EXECUTION_UNKNOWN', { resendHaltedReason: 'REQUESTER_NOT_ELIGIBLE' }))
    expect(halted.panel).toHaveTextContent('조사 필요 — 수동 종료 없음')
    expect(halted.panel).toHaveTextContent('REQUESTER_NOT_ELIGIBLE')
  })

  it('EXECUTED shows the cancellation reference', async () => {
    const executed = await openDetail(item('EXECUTED', { ownerReference: 'pcn_1' }))
    expect(executed.panel).toHaveTextContent('실행됨 — 취소 참조 pcn_1')
  })

  it('OWNER_REJECTED shows the owner code', async () => {
    const rejected = await openDetail(item('OWNER_REJECTED', { ownerResultCode: 'CANCEL_IN_PROGRESS' }))
    expect(rejected.panel).toHaveTextContent('소유 서비스가 거절했습니다: CANCEL_IN_PROGRESS')
  })

  it('D-OPS-010: the reference and reason appear only after the authorized lookup succeeds; a 403 shows the error and no text', async () => {
    const allowed = await openDetail(item('EXECUTED', { ownerReference: 'pcn_1' }), approver, {
      justification: vi.fn(async () => ({ approvalId: 'apr_EXECUTED', purposeReference: 'RECON-2026-0001', reason: 'duplicate charge' })),
    })
    expect(within(allowed.panel).queryByText('RECON-2026-0001')).toBeNull()
    await userEvent.click(within(allowed.panel).getByRole('button', { name: '근거·사유 보기' }))
    expect(await within(allowed.panel).findByText('RECON-2026-0001')).toBeInTheDocument()
    expect(within(allowed.panel).getByText('duplicate charge')).toBeInTheDocument()
    expect(allowed.api.justification).toHaveBeenCalledWith('apr_EXECUTED')
    // S9 비노출: 문구는 화면 상태에만 — 브라우저 저장소·URL·문서 제목에 남지 않는다.
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain('RECON-2026-0001')
    expect(document.title).not.toContain('RECON')
  })

  it('D-OPS-010: for the requester a refused lookup shows the error, never the text', async () => {
    const refused = await openDetail(item('EXECUTED'), { actorId: 'adm_R', roles: ['OPERATOR'] }, {
      justification: vi.fn(async () => { throw apiError(403, 'FORBIDDEN') }),
    })
    await userEvent.click(within(refused.panel).getByRole('button', { name: '근거·사유 보기' }))
    expect(await within(refused.panel).findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(within(refused.panel).queryByText('RECON-2026-0001')).toBeNull()
  })

  it('the justification button is hidden for a viewer who is neither APPROVER nor the requester (display only — the server decides)', async () => {
    const other = await openDetail(item('EXECUTED'), { actorId: 'adm_X', roles: ['SECURITY_ADMIN'] })
    expect(within(other.panel).queryByRole('button', { name: '근거·사유 보기' })).toBeNull()
  })

  it('an APPROVER approves a PENDING owner-service approval only with a 6-digit TOTP; the row names the Payment', async () => {
    const pending = { ...item('PENDING'), decidedBy: null, decidedByDisplayName: null, decidedAt: null, owner: undefined }
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [pending] })), approve: vi.fn(async () => ({ approvalId: 'apr_PENDING', status: 'EXECUTING' })) })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={approver} />)
    const row = await screen.findByRole('row', { name: /OPERATOR_CORRECTION_CANCEL pay_1 PENDING/ })
    const approve = within(row).getByRole('button', { name: '승인' })
    expect(approve).toBeDisabled()
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(approve)
    expect(api.approve).toHaveBeenCalledWith('apr_PENDING', '123456')
  })
})
