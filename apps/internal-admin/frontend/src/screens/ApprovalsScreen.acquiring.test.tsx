import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { AcquiringDetail, ApprovalView, ReacquisitionTargets } from '../api/adminApi'

/**
 * 승인함의 재매입 승인(ACQ-ADMIN-001 B1-A §5.2 — 새 종류 "재매입", 고정 값 vs 현재 값, 예상 순액은 표시용, TOTP 승인·거절). 명시 Fixture: 원 파일 001의 레코드 1·3(세대 0 거절),
 * 레코드 3은 취소 5,000원 → 예상 순액 5,000원.
 */
const F1 = 'ACQ.SIMACQA.TEST.20261105.001'
const pinnedTarget = {
  fileId: F1,
  items: [
    { seq: 1, transactionId: 'ptx_ex1', recordType: 'SALE', generation: 0, status: 'REJECTED', approvedAmountMinor: 10000, cancelledAmountMinor: 0, expectedNetMinor: 10000 },
    { seq: 3, transactionId: 'ptx_ex3', recordType: 'SALE', generation: 0, status: 'REJECTED', approvedAmountMinor: 10000, cancelledAmountMinor: 5000, expectedNetMinor: 5000 },
  ],
}
const acquiring = (patch: Partial<AcquiringDetail> = {}): AcquiringDetail => ({
  originalFileId: F1, reasonCode: 'INSTITUTION_REQUESTED', pinnedTarget, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null, ...patch,
})
const item = (status: string, patch: Partial<AcquiringDetail> = {}, resultCode: string | null = null): ApprovalView => ({
  approvalId: `apr_ACQ_${status}`, kind: 'ACQ_REACQUISITION', targetKind: 'ACQUISITION_FILE', targetActorId: null, targetRef: F1, targetSnapshot: JSON.stringify(pinnedTarget),
  grantedRole: null, causeApprovalId: null, requestedBy: 'adm_R', requestedByDisplayName: '운영자', requestedAt: '2026-11-07T00:00:00Z', expiresAt: '2026-11-08T00:00:00Z',
  status, decidedBy: status === 'PENDING' ? null : 'adm_A', decidedByDisplayName: null, decidedAt: null, resultCode, acquiring: acquiring(patch),
})
const current = (generation3 = 0, eligible3 = true): ReacquisitionTargets => ({
  fileId: F1, readAt: '2026-11-07T00:01:00.000Z',
  items: [
    { seq: 1, transactionId: 'ptx_ex1', recordType: 'SALE', generation: 0, status: 'REJECTED', eligible: true, refusal: null, approvedAmountMinor: 10000, cancelledAmountMinor: 0, expectedNetMinor: 10000 },
    {
      seq: 3, transactionId: 'ptx_ex3', recordType: 'SALE', generation: generation3, status: eligible3 ? 'REJECTED' : 'AWAITING', eligible: eligible3,
      refusal: eligible3 ? null : 'NOT_LATEST_GENERATION', approvedAmountMinor: 10000, cancelledAmountMinor: 5000, expectedNetMinor: eligible3 ? 5000 : null,
    },
  ],
})

async function open(view: ApprovalView, extra: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), ...extra })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  return { api, row }
}

describe('ApprovalsScreen — re-acquisition approvals (ACQ-ADMIN-001 B1-A §5.2)', () => {
  it('lists the new kind "재매입" with the original file; the detail compares pinned vs current records and shows the expected net (display only)', async () => {
    const reacquisitionTargets = vi.fn(async () => current())
    const { row } = await open(item('PENDING'), { reacquisitionTargets })
    expect(within(row).getByText('재매입')).toBeInTheDocument()
    expect(within(row).getByText(F1)).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(reacquisitionTargets).toHaveBeenCalledWith(F1, [1, 3])
    const record3 = await within(panel).findByRole('row', { name: '고정 레코드 3' })
    expect(within(record3).getAllByText('0 · REJECTED')).toHaveLength(2)
    // 받은 취소 5,000 · 예상 순액 5,000(표시용).
    expect(within(record3).getAllByText('5,000원')).toHaveLength(2)
    expect(panel).toHaveTextContent('사유 코드: 매입사 재전송 요청')
    expect(within(panel).queryByText(/승인 시 무효/)).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  // D-ACQA-005(Q-ACQA-09 — rev 0.6, rev 0.5 m-2 "상세에 근거 표시"를 대체): 근거 참조는 D-OPS-010과 같이 APPROVER·요청자 본인에게만 "근거 보기" 별도 조회(서버 판정·조회 감사).
  it('D-ACQA-005: the detail shows the reason code but not the evidence reference; the APPROVER sees it only after the authorized lookup (never stored)', async () => {
    const reacquisitionEvidence = vi.fn(async () => ({ approvalId: 'apr_ACQ_PENDING', evidenceRef: 'INQ-2026-0001' }))
    const { row, api } = await open(item('PENDING'), { reacquisitionTargets: vi.fn(async () => current()), reacquisitionEvidence })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(panel).toHaveTextContent('사유 코드: 매입사 재전송 요청')
    expect(panel).not.toHaveTextContent('INQ-2026-0001')
    expect(api.reacquisitionEvidence).not.toHaveBeenCalled()
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByText('INQ-2026-0001')).toBeInTheDocument()
    expect(reacquisitionEvidence).toHaveBeenCalledWith('apr_ACQ_PENDING')
    expect(panel).not.toHaveTextContent('사유 문구')
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain('INQ-2026-0001')
  })

  it('D-ACQA-005: the requester (OPERATOR, not an APPROVER) also gets the button; a refused lookup (403) shows the error and never the reference', async () => {
    const view = item('EXECUTION_UNKNOWN')
    const reacquisitionEvidence = vi.fn(async () => { throw apiError(403, 'FORBIDDEN') })
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), reacquisitionEvidence })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_R', roles: ['OPERATOR'] }} />)
    await userEvent.click(within(await screen.findByRole('row', { name: /EXECUTION_UNKNOWN/ })).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByRole('alert')).toHaveTextContent('권한이 없습니다')
    expect(panel).not.toHaveTextContent('INQ-2026-0001')
  })

  it('D-ACQA-005: VIEWER, SECURITY_ADMIN and an OPERATOR who is not the requester see no evidence button (display only — the server decides)', async () => {
    for (const viewer of [
      { actorId: 'adm_V', roles: ['VIEWER'] },
      { actorId: 'adm_S', roles: ['SECURITY_ADMIN'] },
      { actorId: 'adm_O', roles: ['OPERATOR'] },
    ]) {
      const view = item('EXECUTION_UNKNOWN')
      const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view) })
      const { unmount } = render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={viewer} />)
      await userEvent.click(within(await screen.findByRole('row', { name: /EXECUTION_UNKNOWN/ })).getByRole('button', { name: '상세' }))
      const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
      expect(within(panel).queryByRole('button', { name: '근거 보기' })).toBeNull()
      expect(panel).not.toHaveTextContent('INQ-2026-0001')
      unmount()
    }
  })

  it('B1-A review n-5: the expected-net cell keeps the pinned value and shows the current value separately (no silent replacement)', async () => {
    const base = current()
    const moved = { ...base, items: base.items.map((i) => (i.seq === 3 ? { ...i, cancelledAmountMinor: 6000, expectedNetMinor: 4000 } : i)) }
    const { row } = await open(item('PENDING'), { reacquisitionTargets: vi.fn(async () => moved) })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    const record3 = await within(panel).findByRole('row', { name: '고정 레코드 3' })
    expect(await within(record3).findByText('현재 4,000원')).toBeInTheDocument()
    expect(within(record3).getAllByText('5,000원')).toHaveLength(2)
    expect(within(within(panel).getByRole('row', { name: '고정 레코드 1' })).getByText('현재 같음')).toBeInTheDocument()
  })

  it('B1-A review m-1: approving when FINANCIAL no longer finds the target (409 TARGET_NOT_FOUND) shows a dedicated message, not a generic error', async () => {
    const approve = vi.fn(async () => { throw apiError(409, 'TARGET_NOT_FOUND') })
    const { row } = await open(item('PENDING'), { approve })
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('대상을 찾을 수 없어 처리하지 않았습니다')
  })

  it('a changed target (new generation / not eligible now) warns that approval will be invalidated and the TOTP consumed', async () => {
    const { row } = await open(item('PENDING'), { reacquisitionTargets: vi.fn(async () => current(1, false)) })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(await within(panel).findByText('승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다')).toBeInTheDocument()
    expect(within(panel).getByRole('row', { name: '고정 레코드 3' })).toHaveTextContent('불가: 이미 새 세대가 있음(재매입 진행 중)')
  })

  it('approve and reject go with the step-up TOTP (same buttons as other approvals)', async () => {
    const approve = vi.fn(async () => ({ approvalId: 'apr_ACQ_PENDING', status: 'EXECUTED' }))
    const { row } = await open(item('PENDING'), { approve })
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    expect(approve).toHaveBeenCalledWith('apr_ACQ_PENDING', '123456')
  })

  // ACQ-ADMIN-001 B2 리뷰 m-1: 승인·거절·철회 후 목록만 다시 읽으면 열린 상세가 PENDING으로 남아 고정 값 대 현재 값 비교를 계속한다 — 상세도 다시 읽는다.
  it('B2 review m-1: after approving the approval shown in the detail panel, the panel is re-read and no longer shows PENDING (no stale comparison)', async () => {
    let state: ApprovalView = item('PENDING')
    const approve = vi.fn(async () => {
      state = item('EXECUTED', { ownerReference: 'ACQ.SIMACQA.TEST.20261105.002' })
      return { approvalId: 'apr_ACQ_PENDING', status: 'EXECUTED' }
    })
    const approvalDetail = vi.fn(async () => state)
    const reacquisitionTargets = vi.fn(async () => current())
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [state] })), approvalDetail, approve, reacquisitionTargets })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
    const row = await screen.findByRole('row', { name: /PENDING/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(within(panel).getByTitle('PENDING')).toBeInTheDocument()
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    const reread = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(await within(reread).findByTitle('EXECUTED')).toBeInTheDocument()
    expect(within(reread).queryByTitle('PENDING')).toBeNull()
    expect(reread).toHaveTextContent('실행됨 — 재매입 파일 ACQ.SIMACQA.TEST.20261105.002 봉인')
    expect(reread).not.toHaveTextContent('예상 순액은 표시용입니다')
    expect(approvalDetail).toHaveBeenCalledTimes(2)
    expect(reacquisitionTargets).toHaveBeenCalledTimes(1)
  })

  it('B2 review m-1: withdrawing my request shown in the detail panel re-reads the detail (WITHDRAWN, not PENDING)', async () => {
    let state: ApprovalView = item('PENDING')
    const withdraw = vi.fn(async () => {
      state = item('WITHDRAWN')
      return { approvalId: 'apr_ACQ_PENDING', status: 'WITHDRAWN' }
    })
    const approvalDetail = vi.fn(async () => state)
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [state] })), approvalDetail, withdraw, reacquisitionTargets: vi.fn(async () => current()) })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_R', roles: ['OPERATOR'] }} />)
    const row = await screen.findByRole('row', { name: /PENDING/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    expect(within(await screen.findByRole('region', { name: '재매입 승인 상세' })).getByTitle('PENDING')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '철회' }))
    const reread = await screen.findByRole('region', { name: '재매입 승인 상세' })
    expect(await within(reread).findByTitle('WITHDRAWN')).toBeInTheDocument()
    expect(within(reread).queryByTitle('PENDING')).toBeNull()
    expect(approvalDetail).toHaveBeenCalledTimes(2)
  })

  it('results: EXECUTED shows the new re-acquisition file, NOT_REQUIRED says closed without a file, OWNER_REJECTED shows the refusal, UNKNOWN re-checks automatically', async () => {
    const cases: [ApprovalView, string][] = [
      [item('EXECUTED', { ownerReference: 'ACQ.SIMACQA.TEST.20261105.002' }), '실행됨 — 재매입 파일 ACQ.SIMACQA.TEST.20261105.002 봉인'],
      [item('EXECUTED', { ownerResultCode: 'NOT_REQUIRED' }, 'NOT_REQUIRED'), '"재매입 불요"로 종결했습니다'],
      [item('OWNER_REJECTED', { ownerResultCode: 'TARGET_CHANGED' }, 'TARGET_CHANGED'), '매입 정보 서비스가 거절했습니다: 대상이 바뀜'],
      [item('EXECUTION_UNKNOWN', { lastFailure: 'HTTP_503' }), '결과 확인 중 — 자동으로 다시 확인합니다'],
    ]
    for (const [view, text] of cases) {
      const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view) })
      const { unmount } = render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_R', roles: ['OPERATOR'] }} />)
      await userEvent.click(within(await screen.findByRole('row', { name: new RegExp(view.status) })).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('region', { name: '재매입 승인 상세' })).toHaveTextContent(text)
      unmount()
    }
  })
})
