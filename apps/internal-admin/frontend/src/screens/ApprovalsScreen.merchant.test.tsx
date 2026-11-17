import { describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { ApprovalView, MerchantApprovalDetail } from '../api/adminApi'
import { hasUnresolvedRequest, createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'

/**
 * 승인함의 가맹점 계약 승인(MERCHANT-OPS-002 §8.2 B2-2-12 — §9.6): 종류 라벨, 상세(Scope·KST 날짜·"0시 종료(미포함)"·Revision 등록자), 등록자 본인이면 승인 버튼 대신 안내,
 * "근거 보기"(APPROVER·요청자만 — 서버 판정), 결과 문구. 명시 Fixture: 가맹점 `mer_…01`, 계약 `ctr_…01` Revision 2(2026-11-11 00:00 KST 시작 — 2027-01-01 0시 종료).
 */
const MER = `mer_${'0'.repeat(25)}1`
const CTR = `ctr_${'0'.repeat(25)}1`
const REGISTRANT = 'adm_RG'
const pinnedTarget = {
  merchantId: MER, merchantName: '데모 가맹점', contractId: CTR, revisionNo: 2, scopeKind: 'MERCHANT', scopeSiteIds: [], effectiveFrom: '2026-11-10T15:00:00.000Z',
  effectiveTo: '2026-12-31T15:00:00.000Z', approved: false, registeredBy: `admin:${REGISTRANT}`,
}
const merchant = (patch: Partial<MerchantApprovalDetail> = {}): MerchantApprovalDetail => ({
  commandKind: 'CONTRACT_APPROVAL', merchantId: MER, pinnedTarget, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null, ...patch,
})
const item = (status: string, patch: Partial<MerchantApprovalDetail> = {}, resultCode: string | null = null): ApprovalView => ({
  // 응답 Fixture: MC-3a 서버 응답의 targetMerchantId(내부 키 — 응답 포함·화면 미표시, D-BIZLOOKUP-001). 기대값 아님.
  approvalId: `apr_MOP_${status}`, kind: 'MERCHANT_CONTRACT_APPROVAL', targetKind: 'MERCHANT', targetActorId: null, targetRef: MER, targetMerchantId: MER, targetSnapshot: JSON.stringify(pinnedTarget),
  grantedRole: null, causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z',
  status, decidedBy: status === 'PENDING' ? null : 'adm_A', decidedByDisplayName: null, decidedAt: null, resultCode, merchant: merchant(patch),
})

async function open(view: ApprovalView, viewer = { actorId: 'adm_A', roles: ['APPROVER'] }, extra: Parameters<typeof fakeApi>[0] = {}) {
  // MC-3c: 화면이 가맹점 대상 라벨을 묶음 호출한다(POST /api/merchant-labels) — 현재 상호·코드 대역.
  const merchantLabels = vi.fn(async (ids: string[]) => ({ items: ids.map((merchantId) => ({ merchantId, name: '데모상점', code: 'demoshop01' })), labels: 'OK' as const }))
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), merchantLabels, ...extra })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={viewer} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  return { api, row }
}

describe('ApprovalsScreen — merchant contract approvals (MERCHANT-OPS-002 §9.6)', () => {
  it('lists "가맹점 계약 승인" with the merchant; the detail shows scope, KST dates with the exclusive end note, requester and the revision registrant; no evidence in the detail', async () => {
    const { row } = await open(item('PENDING'))
    expect(within(row).getByText('가맹점 계약 승인')).toBeInTheDocument()
    // 기대값 변경(MERCHANT-CODE-001 §8 MC-3-20·§5.6.2): D-ADMIN-UI-004 ③·D-MCODE-003 ① — 화면에 내부 키 미표시(rev 0.4.6 §5.6, APPROVAL-TARGET-KEYS-001 합침). getByText(MER) → 라벨 텍스트 + DOM mer_ 0건.
    expect(await within(row).findByText('데모상점 · demoshop01')).toBeInTheDocument()
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '가맹점 계약 승인 상세' })
    expect(panel).toHaveTextContent(`${CTR} #2`)
    expect(panel).toHaveTextContent('범위: 가맹점 전체')
    expect(panel).toHaveTextContent('시작일(KST): 2026-11-11')
    expect(panel).toHaveTextContent('종료일(KST): 2027-01-01 — 이 날 0시 종료(미포함)')
    expect(panel).toHaveTextContent(`Revision 등록자: admin:${REGISTRANT}`)
    expect(panel).toHaveTextContent('요청자: 운영자')
    expect(panel).not.toHaveTextContent('CONTRACT-2026')
  })

  it('D-MOPS-008 ③: the registrant sees "등록자는 승인할 수 없습니다" instead of the approve button; another approver sees the button', async () => {
    const { row } = await open(item('PENDING'), { actorId: REGISTRANT, roles: ['OPERATOR', 'APPROVER'] })
    expect(within(row).queryByRole('button', { name: '승인' })).toBeNull()
    expect(within(row).getByText('등록자는 승인할 수 없습니다')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    expect(await screen.findByText(/등록자는 승인할 수 없습니다 — 다른 승인자가/)).toBeInTheDocument()
  })

  it('an approver approves with the TOTP; a server 403 APPROVER_IS_REGISTRANT is shown with its message', async () => {
    const approve = vi.fn(async () => { throw apiError(403, 'APPROVER_IS_REGISTRANT') })
    const { row } = await open(item('PENDING'), undefined, { approve })
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(row).getByRole('button', { name: '승인' }))
    expect(approve).toHaveBeenCalledWith('apr_MOP_PENDING', '123456')
    expect(await screen.findByText(/등록자는 승인할 수 없습니다\(이 Revision을 등록한 계정입니다\)/)).toBeInTheDocument()
  })

  it('"근거 보기" only for APPROVER or the requester; the reference stays in screen state only', async () => {
    const merchantApprovalEvidence = vi.fn(async () => ({ approvalId: 'apr_MOP_PENDING', evidenceRef: 'CONTRACT-2026/0001#p1' }))
    const { row } = await open(item('PENDING'), undefined, { merchantApprovalEvidence })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '가맹점 계약 승인 상세' })
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByText('CONTRACT-2026/0001#p1')).toBeInTheDocument()
    expect(storageIsEmpty()).toBe(true)
  })

  it('a VIEWER who is not the requester gets no "근거 보기" button', async () => {
    const { row } = await open(item('PENDING'), { actorId: 'adm_V', roles: ['VIEWER'] })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '가맹점 계약 승인 상세' })
    expect(within(panel).queryByRole('button', { name: '근거 보기' })).toBeNull()
  })

  it('result wording: EXECUTED reference, OWNER_REJECTED (expired), EXECUTION_UNKNOWN, INVALIDATED', async () => {
    for (const [view, text] of [
      [item('EXECUTED', { ownerReference: `${CTR}#2` }), `승인됨 — ${CTR}#2`],
      [item('OWNER_REJECTED', { ownerResultCode: 'APPROVAL_EXPIRED' }, 'APPROVAL_EXPIRED'), '실행 기한이 지나 처리되지 않았습니다 — 다시 요청하세요'],
      [item('EXECUTION_UNKNOWN', { lastFailure: 'TIMEOUT' }), '결과 확인 중(자동 재확인)'],
      [item('INVALIDATED', {}, 'TARGET_CHANGED'), '요청 뒤 대상이 바뀌어 무효'],
    ] as const) {
      const { row } = await open(view)
      await userEvent.click(within(row).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('region', { name: '가맹점 계약 승인 상세' })).toHaveTextContent(text)
      cleanup()
    }
  })
})

describe('ApprovalsScreen — B2-3 first Default MID approvals (MERCHANT-OPS-002 §9.6)', () => {
  const midPinned = {
    merchantId: MER, merchantName: '데모 가맹점', accountCount: 0, contractId: CTR, agreementId: `csa_${'0'.repeat(25)}1`, revisionNo: 1, approved: true,
    effectiveFrom: '2026-11-08T15:00:00.000Z', effectiveTo: null, primarySiteId: `site_${'0'.repeat(25)}1`, channelType: 'WEB',
  }
  const midItem = (status: string, patch: Partial<MerchantApprovalDetail> = {}): ApprovalView => ({
    ...item(status, { commandKind: 'FIRST_MID_ISSUANCE', pinnedTarget: midPinned, ...patch }), approvalId: `apr_MID_${status}`, kind: 'MERCHANT_FIRST_MID_ISSUANCE',
    targetSnapshot: JSON.stringify(midPinned),
  })

  it('lists "첫 MID 발급"; the detail shows contract·revision, CSA, PRIMARY Site and channel; no registrant notice even for the contract registrant; EXECUTED shows the mid_', async () => {
    const { row } = await open(midItem('PENDING'), { actorId: REGISTRANT, roles: ['APPROVER'] })
    expect(within(row).getByText('첫 MID 발급')).toBeInTheDocument()
    expect(within(row).getByRole('button', { name: '승인' })).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '첫 MID 발급 승인 상세' })
    expect(panel).toHaveTextContent(`${CTR} #1`)
    expect(panel).toHaveTextContent(`CSA: csa_${'0'.repeat(25)}1`)
    expect(panel).toHaveTextContent(`PRIMARY Site: site_${'0'.repeat(25)}1 (웹)`)
    cleanup()
    const { row: done } = await open(midItem('EXECUTED', { ownerReference: `mid_${'0'.repeat(25)}1` }))
    await userEvent.click(within(done).getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('region', { name: '첫 MID 발급 승인 상세' })).toHaveTextContent(`발급됨 — mid_${'0'.repeat(25)}1`)
  })
})

describe('B2-1 review n-a: unresolved request notice at the session boundary', () => {
  it('a locked registration step or revision form (busy/unknown) counts; idle and succeeded forms do not', () => {
    const a = createScreenMemory()
    a.write('business.status', { kind: 'idle' })
    a.write('revision.ctr_x.step.status', { kind: 'succeeded' })
    expect(hasUnresolvedRequest(a)).toBe(false)
    const b = createScreenMemory()
    b.write('revision.ctr_x.step.status', { kind: 'unknown' })
    expect(hasUnresolvedRequest(a, b)).toBe(true)
  })
})
