import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { fakeApi } from '../test/fakeApi'
import type { ApprovalView, MerchantApprovalDetail } from '../api/adminApi'
import { ACTIVATION_REJECTION_MESSAGES } from '../errors/messages'

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
  approvalId: `apr_MCODE_${status}`, kind: 'MERCHANT_CODE_CHANGE', targetKind: 'MERCHANT', targetActorId: null, targetRef: MER, targetSnapshot: JSON.stringify(pinned),
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
})
