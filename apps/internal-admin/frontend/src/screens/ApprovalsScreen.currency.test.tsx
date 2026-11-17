import { describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { ApprovalView, MerchantApprovalDetail } from '../api/adminApi'

/**
 * 승인함의 B3-2 통화 활성화(MERCHANT-OPS-002 §8.4 B3-2-6 — D-MOPS-009 ② "승인 화면에 표시"): 종류 라벨 "통화 활성화", 상세 pinned — **"효력 시작: 승인 실행 즉시(MASTER 실행 시각으로 확정)"**
 * 또는 **"효력 시작: YYYY-MM-DD 00:00 KST부터"**, 종료일 "이 날 0시 종료(미포함)", 근거 라벨 "기관(카드사/VAN) 회신 참조", 결과 문구(`CURRENCY_ENABLEMENT_RETROACTIVE` "효력 시작 시각이 이미
 * 지났습니다 — 다시 요청하세요"). 등록자 규칙 없음(1인 등록 단계 없음 — §9.27). Demo 식별자(업무 의미 없는 고정값).
 */
const MER = `mer_${'0'.repeat(25)}1`
const MID = `mid_${'0'.repeat(25)}1`
const CUR = `cur_${'0'.repeat(25)}1`
const atExecution = {
  merchantId: MER, merchantName: '데모 가맹점', merchantAccountId: MID, accountLifecycle: 'ACTIVE', currency: 'KRW', effectiveFromMode: 'AT_EXECUTION', effectiveFrom: null, effectiveTo: null,
}
const onDate = { ...atExecution, effectiveFromMode: 'DATE', effectiveFrom: '2026-11-14', effectiveTo: '2026-12-01' }
const merchant = (pinnedTarget: unknown, patch: Partial<MerchantApprovalDetail> = {}): MerchantApprovalDetail => ({
  commandKind: 'CURRENCY_ENABLEMENT', merchantId: MER, pinnedTarget, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null, ...patch,
})
const item = (status: string, pinned: object = atExecution, patch: Partial<MerchantApprovalDetail> = {}, resultCode: string | null = null): ApprovalView => ({
  // Fixture 변경(MERCHANT-CODE-001 §5.6.2·MC-3a 응답 모양 — 리뷰 NIT-4): 승인 응답에 `targetMerchantId`(가맹점 대상 = mer_, §5.6.4 승인 응답)가 있다. 기대값은 바뀌지 않았다(완화 없음).
  approvalId: `apr_CUR_${status}`, kind: 'MERCHANT_CURRENCY_ENABLEMENT', targetKind: 'MERCHANT', targetActorId: null, targetRef: MER, targetMerchantId: MER, targetSnapshot: JSON.stringify(pinned),
  grantedRole: null, causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z',
  status, decidedBy: status === 'PENDING' ? null : 'adm_A', decidedByDisplayName: null, decidedAt: null, resultCode, merchant: merchant(pinned, patch),
})

async function open(view: ApprovalView, viewer = { actorId: 'adm_A', roles: ['APPROVER'] }, extra: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), ...extra })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={viewer} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  return { api, row }
}

describe('ApprovalsScreen — B3-2 currency enablement (MERCHANT-OPS-002 §8.4 B3-2-6)', () => {
  it('"통화 활성화" AT_EXECUTION: the detail says "효력 시작: 승인 실행 즉시(MASTER 실행 시각으로 확정)", MID, KRW, no end; no evidence in the detail', async () => {
    const { row } = await open(item('PENDING'))
    expect(within(row).getByText('통화 활성화')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '통화 활성화 승인 상세' })
    expect(panel).toHaveTextContent('효력 시작: 승인 실행 즉시(MASTER 실행 시각으로 확정)')
    expect(panel).toHaveTextContent(`MID: ${MID}`)
    expect(panel).toHaveTextContent('통화: KRW')
    expect(panel).toHaveTextContent('종료일: 없음')
    expect(panel).toHaveTextContent('요청자: 운영자')
    expect(panel).not.toHaveTextContent('VAN-REPLY')
  })

  it('DATE: the detail says "효력 시작: 2026-11-14 00:00 KST부터" and the exclusive end date', async () => {
    const { row } = await open(item('PENDING', onDate))
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '통화 활성화 승인 상세' })
    expect(panel).toHaveTextContent('효력 시작: 2026-11-14 00:00 KST부터')
    expect(panel).toHaveTextContent('종료일(KST): 2026-12-01 — 이 날 0시 종료(미포함)')
    expect(panel).not.toHaveTextContent('승인 실행 즉시')
  })

  it('no registrant rule: an OPERATOR + APPROVER viewer (not the requester) sees the approve button and no registrant notice', async () => {
    const { row } = await open(item('PENDING'), { actorId: 'adm_BOTH', roles: ['OPERATOR', 'APPROVER'] })
    expect(within(row).getByRole('button', { name: '승인' })).toBeInTheDocument()
    expect(within(row).queryByText('등록자는 승인할 수 없습니다')).toBeNull()
  })

  it('result wording: EXECUTED cur_, CURRENCY_ENABLEMENT_RETROACTIVE "효력 시작 시각이 이미 지났습니다 — 다시 요청하세요", OVERLAP', async () => {
    for (const [view, text] of [
      [item('EXECUTED', atExecution, { ownerReference: CUR }), `활성화됨 — ${CUR}`],
      [item('OWNER_REJECTED', onDate, { ownerResultCode: 'CURRENCY_ENABLEMENT_RETROACTIVE' }, 'CURRENCY_ENABLEMENT_RETROACTIVE'), '효력 시작 시각이 이미 지났습니다 — 다시 요청하세요'],
      [item('OWNER_REJECTED', atExecution, { ownerResultCode: 'CURRENCY_ENABLEMENT_OVERLAP' }, 'CURRENCY_ENABLEMENT_OVERLAP'), '같은 통화의 활성화 기간이 이미 있습니다'],
    ] as const) {
      const { row } = await open(view)
      await userEvent.click(within(row).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('region', { name: '통화 활성화 승인 상세' })).toHaveTextContent(text)
      cleanup()
    }
  })

  it('"근거 보기" labels the reference as the institution reply; screen state only', async () => {
    const merchantApprovalEvidence = vi.fn(async () => ({ approvalId: 'x', evidenceRef: 'VAN-REPLY/2026-0001' }))
    const { row } = await open(item('PENDING'), undefined, { merchantApprovalEvidence })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '통화 활성화 승인 상세' })
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByText('VAN-REPLY/2026-0001')).toBeInTheDocument()
    expect(within(panel).getByText('기관(카드사/VAN) 회신 참조')).toBeInTheDocument()
    expect(storageIsEmpty()).toBe(true)
  })
})
