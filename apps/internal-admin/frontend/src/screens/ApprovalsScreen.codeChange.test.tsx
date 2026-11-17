import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import type { ApprovalView, MerchantApprovalDetail, MerchantTargetLabelPage } from '../api/adminApi'
import { ACTIVATION_REJECTION_MESSAGES } from '../errors/messages'
import { statusText } from '../components/MerchantContractApprovalDetail'

/**
 * MERCHANT-CODE-001 §8 MC-1b-9 — 승인함의 가맹점 코드 변경(§5.5): 종류 라벨 "가맹점 코드 변경", 상세 "현재 → 새 코드"·사유, 등록자 안내 문구 0건(D-MCODE-002 Q-MC-10),
 * 상세 패널에 내부 키(mer_…) 0건(D-ADMIN-UI-004 ③), MASTER 거절 문구 매핑(MISMATCH = 점검 문구). Demo 식별자.
 */
const MER = `mer_${'0'.repeat(25)}1`
const pinned = { merchantId: MER, merchantName: '데모 가맹점', currentCode: 'shopabcd12', newCode: 'shopnew2026', reasonCode: 'TYPO_CORRECTION', contractApproved: false, codeChangeCount: 0 }
const merchant = (patch: Partial<MerchantApprovalDetail> = {}): MerchantApprovalDetail => ({
  commandKind: 'MERCHANT_CODE_CHANGE', merchantId: MER, pinnedTarget: pinned, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null, ...patch,
})
const item = (status: string, resultCode: string | null = null): ApprovalView => ({
  // Fixture 변경(MERCHANT-CODE-001 §5.6.2·MC-3a 응답 모양 — 리뷰 NIT-4): 승인 응답에 `targetMerchantId`(가맹점 대상 = mer_, §5.6.4 승인 응답)가 있다. 기대값은 바뀌지 않았다(완화 없음).
  approvalId: `apr_MCODE_${status}`, kind: 'MERCHANT_CODE_CHANGE', targetKind: 'MERCHANT', targetActorId: null, targetRef: MER, targetMerchantId: MER, targetSnapshot: JSON.stringify(pinned),
  grantedRole: null, causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z',
  status, decidedBy: status === 'PENDING' ? null : 'adm_A', decidedByDisplayName: null, decidedAt: null, resultCode, merchant: merchant(),
})

async function openPanel(view: ApprovalView, viewer = { actorId: 'adm_A', roles: ['APPROVER'] }) {
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view) })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={viewer} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  expect(within(row).getByText('가맹점 코드 변경')).toBeInTheDocument()
  await userEvent.click(within(row).getByRole('button', { name: '상세' }))
  return screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })
}

describe('ApprovalsScreen — MC-1b merchant code change (MERCHANT-CODE-001 §5.5)', () => {
  it('the detail shows "현재 → 새 코드" and the reason; no registrant notice even when the viewer is the merchant registrant; no mer_ in the panel', async () => {
    const panel = await openPanel(item('PENDING'), { actorId: 'adm_X', roles: ['APPROVER'] })
    expect(panel).toHaveTextContent('코드: shopabcd12 → shopnew2026')
    expect(panel).toHaveTextContent('사유: 오타 수정')
    expect(panel).toHaveTextContent('가맹점: 데모 가맹점')
    expect(panel).not.toHaveTextContent('등록자')
    expect(panel.innerHTML).not.toContain('mer_')
  })

  it('OWNER_REJECTED MERCHANT_CODE_MISMATCH reads as the inspection text; TAKEN / LOCKED / ALREADY_CHANGED / RESERVED map to the §5.5 texts', async () => {
    const panel = await openPanel(item('OWNER_REJECTED', 'MERCHANT_CODE_MISMATCH'))
    expect(panel).toHaveTextContent('시스템 점검 필요(관리자에게 알림)')
    expect(ACTIVATION_REJECTION_MESSAGES.MERCHANT_CODE_TAKEN).toBe('이미 사용 중이거나 사용했던 코드입니다')
    expect(ACTIVATION_REJECTION_MESSAGES.MERCHANT_CODE_LOCKED).toBe('계약 승인 뒤에는 코드를 바꿀 수 없습니다')
    expect(ACTIVATION_REJECTION_MESSAGES.MERCHANT_CODE_ALREADY_CHANGED).toBe('코드는 한 번만 바꿀 수 있습니다')
    expect(ACTIVATION_REJECTION_MESSAGES.MERCHANT_CODE_RESERVED).toBe('사용할 수 없는 코드입니다')
  })

  /**
   * reviewFix1 MAJOR-1(§5.6.2 "어떤 분기도 내부 키를 텍스트에 넣지 않는다"·MC-3-19·D-ADMIN-UI-004 ③): 코드 변경 EXECUTED의 소유 참조(OwnerExecution ownerReference)는 `^mer_<ULID>$`다.
   * 상태 문구는 참조 대신 "변경됨 — <pinned 새 코드>"(pinned에 새 코드가 없으면 "변경됨"). MC-3a 응답 모양: targetRef = 요청 시점 코드, targetMerchantId = mer_.
   */
  const executedLabels = vi.fn(async (ids: string[]): Promise<MerchantTargetLabelPage> => ({
    items: ids.includes(MER) ? [{ merchantId: MER, name: '데모 가맹점', code: 'shopnew2026' }] : [], labels: 'OK',
  }))
  const mc3a = (status: string, patch: Partial<MerchantApprovalDetail> = {}, snapshot: object = pinned): ApprovalView => ({
    ...item(status), targetRef: 'shopabcd12', targetMerchantId: MER, targetSnapshot: JSON.stringify(snapshot), merchant: { ...merchant(patch), pinnedTarget: snapshot },
  })

  it('MAJOR-1 approver path: approving with the detail open re-reads it as EXECUTED → "변경됨 — shopnew2026", no mer_ anywhere in the DOM', async () => {
    const pending = mc3a('PENDING')
    const executed = mc3a('EXECUTED', { ownerReference: MER, approvedAt: '2026-11-10T01:00:00Z' })
    let done = false
    const approve = vi.fn(async () => { done = true; return { approvalId: pending.approvalId, status: 'EXECUTED' } })
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [done ? executed : pending] })), approvalDetail: vi.fn(async () => (done ? executed : pending)), approve, merchantLabels: executedLabels,
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
    const row = await screen.findByRole('row', { name: /PENDING/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    await screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    expect(approve).toHaveBeenCalledWith(pending.approvalId, '123456')
    const panel = await screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })
    await vi.waitFor(() => expect(panel).toHaveTextContent('변경됨 — shopnew2026'))
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('MAJOR-1 "내 요청" path: an EXECUTED code change detail reads "변경됨 — shopnew2026", no mer_ anywhere in the DOM', async () => {
    const executed = mc3a('EXECUTED', { ownerReference: MER })
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [executed] })), approvalDetail: vi.fn(async () => executed), merchantLabels: executedLabels })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_REQ', roles: ['OPERATOR'] }} />)
    const row = await screen.findByRole('row', { name: /EXECUTED/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })
    expect(panel).toHaveTextContent('변경됨 — shopnew2026')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('MAJOR-1: pinned without a new code (unreadable snapshot) → "변경됨" only, never the reference', async () => {
    const { newCode: _dropped, ...noNewCode } = pinned
    const executed = mc3a('EXECUTED', { ownerReference: MER }, noNewCode)
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [executed] })), approvalDetail: vi.fn(async () => executed), merchantLabels: executedLabels })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_REQ', roles: ['OPERATOR'] }} />)
    const row = await screen.findByRole('row', { name: /EXECUTED/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })
    expect(within(panel).getByText('변경됨')).toBeInTheDocument()
    expect(panel).not.toHaveTextContent('변경됨 —')
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
  })

  it('MAJOR-1 last defence: any status text that would carry an internal key shows "—" for the reference; other kinds keep their reference as is', () => {
    const contract = (ownerReference: string): ApprovalView => ({ ...item('EXECUTED'), kind: 'MERCHANT_CONTRACT_APPROVAL', merchant: merchant({ commandKind: 'CONTRACT_APPROVAL', ownerReference }) })
    expect(statusText(contract(MER))).toBe('승인됨 — —')
    expect(statusText(contract('biz_x'))).toBe('승인됨 — —')
    expect(statusText(contract(`ctr_${'0'.repeat(25)}1#2`))).toBe(`승인됨 — ctr_${'0'.repeat(25)}1#2`)
    expect(statusText({ ...item('OWNER_REJECTED', MER), merchant: merchant({ ownerResultCode: MER }) })).toBe('가맹점 정보 서비스가 거절했습니다: —')
    expect(statusText({ ...item('OWNER_REJECTED', 'MERCHANT_CODE_TAKEN') })).toBe('가맹점 정보 서비스가 거절했습니다: 이미 사용 중이거나 사용했던 코드입니다')
  })
})
