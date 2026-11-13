import { describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { fakeApi, storageIsEmpty } from '../test/fakeApi'
import type { ApprovalView, MerchantApprovalDetail } from '../api/adminApi'

/**
 * 승인함의 B3-1 종류(MERCHANT-OPS-002 §8.3 B3-1 9 — §9.17): 종류 라벨 "결제수단 개통 승인"·"Site 심사 승인", pinned 상세(대상 상태·PRIMARY·Site·등록자), "근거 보기"(라벨이 의미를 말한다 —
 * 기관(카드사/VAN) 회신 참조 / PG 심사 근거), 결과 문구. Demo 식별자(업무 의미 없는 고정값).
 * B3-1F(§8.4 B3-1F-5 — D-MOPS-009 ③): 시청자 = pinned 등록자면 승인 버튼 대신 "등록자는 승인할 수 없습니다"(계약 승인과 같은 문구·같은 표시 — 권위 판정은 서버 TX1·MASTER),
 * 새 MASTER 거절 문구 두 개. 이전 "Q-MOPS-24 답 전 — 등록자에게도 승인 버튼" 고정 시험은 사용자 결정에 따라 반대 기대로 바뀌었다(기대 반전 4건 중 Vitest 1건).
 */
const MER = `mer_${'0'.repeat(25)}1`
const MID = `mid_${'0'.repeat(25)}1`
const ACT = `act_${'0'.repeat(25)}1`
const SITE = `site_${'0'.repeat(25)}1`
const SITE2 = `site_${'0'.repeat(25)}2`
const REGISTRANT = 'adm_SR'
const activationPinned = {
  merchantId: MER, merchantName: '데모 가맹점', merchantAccountId: MID, activationId: ACT, paymentMethod: 'CARD', status: 'UNDER_REVIEW', primarySiteId: SITE,
  registeredBy: `admin:${REGISTRANT}`,
}
const sitePinned = {
  merchantId: MER, merchantName: '데모 가맹점', merchantSiteId: SITE2, channelType: 'WEB', reviewStatus: 'PENDING', reasonCode: 'REVIEW_PASSED', registeredBy: `admin:${REGISTRANT}`,
}
const merchant = (commandKind: string, pinnedTarget: unknown, patch: Partial<MerchantApprovalDetail> = {}): MerchantApprovalDetail => ({
  commandKind, merchantId: MER, pinnedTarget, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null, ...patch,
})
const item = (kind: string, status: string, patch: Partial<MerchantApprovalDetail> = {}, resultCode: string | null = null): ApprovalView => {
  const activation = kind === 'MERCHANT_PAYMENT_METHOD_ACTIVATION'
  const pinned = activation ? activationPinned : sitePinned
  return {
    approvalId: `apr_${activation ? 'ACT' : 'SRV'}_${status}`, kind, targetKind: 'MERCHANT', targetActorId: null, targetRef: MER, targetSnapshot: JSON.stringify(pinned),
    grantedRole: null, causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z',
    status, decidedBy: status === 'PENDING' ? null : 'adm_A', decidedByDisplayName: null, decidedAt: null, resultCode,
    merchant: merchant(activation ? 'PAYMENT_METHOD_ACTIVATION' : 'SITE_REVIEW_APPROVAL', pinned, patch),
  }
}

async function open(view: ApprovalView, viewer = { actorId: 'adm_A', roles: ['APPROVER'] }, extra: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [view] })), approvalDetail: vi.fn(async () => view), ...extra })
  render(<ApprovalsScreen api={api} view="pending" canDecide viewer={viewer} />)
  const row = await screen.findByRole('row', { name: new RegExp(view.status) })
  return { api, row }
}

describe('ApprovalsScreen — B3-1 activation kinds (MERCHANT-OPS-002 §9.17)', () => {
  it('"결제수단 개통 승인": the detail shows MID, activation (card · under review), PRIMARY site, requester and the registrant; no evidence in the detail', async () => {
    const { row } = await open(item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'PENDING'))
    expect(within(row).getByText('결제수단 개통 승인')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '결제수단 개통 승인 상세' })
    expect(panel).toHaveTextContent(`MID: ${MID}`)
    expect(panel).toHaveTextContent(`개통: ${ACT} (카드 · 요청 시점 심사 중)`)
    expect(panel).toHaveTextContent(`대표(PRIMARY) Site: ${SITE}`)
    expect(panel).toHaveTextContent(`등록자: admin:${REGISTRANT}`)
    expect(panel).toHaveTextContent('요청자: 운영자')
    expect(panel).not.toHaveTextContent('VAN-REPLY')
  })

  it('D-MOPS-009 ③: the activation registrant sees the registrant notice instead of the approve button', async () => {
    // 기대 반전(MERCHANT-OPS-002 §8.4 B3-1F-6 — D-MOPS-009 ③ 사용자 결정, 기대 완화 아님): 이전 시험 "Q-MOPS-24 unanswered: the registrant sees the approve button and
    // no registrant notice (display only)"는 승인 버튼이 보이고 안내가 없기를 기대했다.
    const { row } = await open(item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'PENDING'), { actorId: REGISTRANT, roles: ['OPERATOR', 'APPROVER'] })
    expect(within(row).queryByRole('button', { name: '승인' })).toBeNull()
    expect(within(row).getByText('등록자는 승인할 수 없습니다')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    await screen.findByRole('region', { name: '결제수단 개통 승인 상세' })
    expect(screen.getByText(/등록자는 승인할 수 없습니다 — 다른 승인자가/)).toBeInTheDocument()
  })

  it('D-MOPS-009 ③: the site registrant sees the registrant notice instead of the approve button (SUSPENDED re-approval too — D-MOPS-010 ②)', async () => {
    for (const review of ['PENDING', 'SUSPENDED']) {
      const view = item('MERCHANT_SITE_REVIEW_APPROVAL', 'PENDING', { pinnedTarget: { ...sitePinned, reviewStatus: review } })
      const { row } = await open({ ...view, targetSnapshot: JSON.stringify({ ...sitePinned, reviewStatus: review }) }, { actorId: REGISTRANT, roles: ['OPERATOR', 'APPROVER'] })
      expect(within(row).queryByRole('button', { name: '승인' })).toBeNull()
      expect(within(row).getByText('등록자는 승인할 수 없습니다')).toBeInTheDocument()
      await userEvent.click(within(row).getByRole('button', { name: '상세' }))
      await screen.findByRole('region', { name: 'Site 심사 승인 상세' })
      expect(screen.getByText(/등록자는 승인할 수 없습니다 — 다른 승인자가/)).toBeInTheDocument()
      cleanup()
    }
  })

  it('a non-registrant APPROVER still sees the approve button and no registrant notice for both B3-1 kinds', async () => {
    for (const kind of ['MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL']) {
      const { row } = await open(item(kind, 'PENDING'), { actorId: 'adm_A', roles: ['APPROVER'] })
      expect(within(row).getByRole('button', { name: '승인' })).toBeInTheDocument()
      expect(within(row).queryByText('등록자는 승인할 수 없습니다')).toBeNull()
      cleanup()
    }
  })

  it('rejection wording for APPROVER_IS_ACTIVATION_REGISTRANT and APPROVER_IS_SITE_REGISTRANT (MASTER defect path)', async () => {
    for (const [view, region, text] of [
      [item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'OWNER_REJECTED', { ownerResultCode: 'APPROVER_IS_ACTIVATION_REGISTRANT' }, 'APPROVER_IS_ACTIVATION_REGISTRANT'),
        '결제수단 개통 승인 상세', '개통 등록자는 승인할 수 없습니다'],
      [item('MERCHANT_SITE_REVIEW_APPROVAL', 'OWNER_REJECTED', { ownerResultCode: 'APPROVER_IS_SITE_REGISTRANT' }, 'APPROVER_IS_SITE_REGISTRANT'),
        'Site 심사 승인 상세', 'Site 등록자는 심사 승인할 수 없습니다'],
    ] as const) {
      const { row } = await open(view)
      await userEvent.click(within(row).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('region', { name: region })).toHaveTextContent(text)
      cleanup()
    }
  })

  it('"근거 보기" labels the reference as the institution reply (activation) or the PG review basis (site review); screen state only', async () => {
    const merchantApprovalEvidence = vi.fn(async () => ({ approvalId: 'x', evidenceRef: 'VAN-REPLY/2026-0001' }))
    const { row } = await open(item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'PENDING'), undefined, { merchantApprovalEvidence })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '결제수단 개통 승인 상세' })
    await userEvent.click(within(panel).getByRole('button', { name: '근거 보기' }))
    expect(await within(panel).findByText('VAN-REPLY/2026-0001')).toBeInTheDocument()
    expect(within(panel).getByText('기관(카드사/VAN) 회신 참조')).toBeInTheDocument()
    expect(storageIsEmpty()).toBe(true)
    cleanup()
    const siteEvidence = vi.fn(async () => ({ approvalId: 'y', evidenceRef: 'PG-REVIEW/2026-0001' }))
    const { row: siteRow } = await open(item('MERCHANT_SITE_REVIEW_APPROVAL', 'PENDING'), undefined, { merchantApprovalEvidence: siteEvidence })
    await userEvent.click(within(siteRow).getByRole('button', { name: '상세' }))
    const sitePanel = await screen.findByRole('region', { name: 'Site 심사 승인 상세' })
    await userEvent.click(within(sitePanel).getByRole('button', { name: '근거 보기' }))
    expect(await within(sitePanel).findByText('PG-REVIEW/2026-0001')).toBeInTheDocument()
    expect(within(sitePanel).getByText('PG 심사 근거')).toBeInTheDocument()
  })

  it('"Site 심사 승인": the detail shows the site, channel, review status at request, reason and registrant; no channel identifier', async () => {
    const { row } = await open(item('MERCHANT_SITE_REVIEW_APPROVAL', 'PENDING'))
    expect(within(row).getByText('Site 심사 승인')).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: 'Site 심사 승인 상세' })
    expect(panel).toHaveTextContent(`Site: ${SITE2} (웹)`)
    expect(panel).toHaveTextContent('요청 시점 심사 상태: 대기')
    expect(panel).toHaveTextContent('사유: 심사 통과')
    expect(panel).toHaveTextContent(`등록자: admin:${REGISTRANT}`)
  })

  it('result wording: activation EXECUTED act_, PRIMARY_SITE_MISMATCH, site review EXECUTED site_#n, SITE_REVIEW_TRANSITION_NOT_ALLOWED', async () => {
    for (const [view, region, text] of [
      [item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'EXECUTED', { ownerReference: ACT }), '결제수단 개통 승인 상세', `개통됨 — ${ACT}`],
      [item('MERCHANT_PAYMENT_METHOD_ACTIVATION', 'OWNER_REJECTED', { ownerResultCode: 'PRIMARY_SITE_MISMATCH' }, 'PRIMARY_SITE_MISMATCH'), '결제수단 개통 승인 상세',
        '승인 요청 뒤 대표(PRIMARY) Site가 바뀌었습니다'],
      [item('MERCHANT_SITE_REVIEW_APPROVAL', 'EXECUTED', { ownerReference: `${SITE2}#1` }), 'Site 심사 승인 상세', `심사 승인됨 — ${SITE2}#1`],
      [item('MERCHANT_SITE_REVIEW_APPROVAL', 'OWNER_REJECTED', { ownerResultCode: 'SITE_REVIEW_TRANSITION_NOT_ALLOWED' }, 'SITE_REVIEW_TRANSITION_NOT_ALLOWED'), 'Site 심사 승인 상세',
        '거절된 Site는 되돌릴 수 없음'],
    ] as const) {
      const { row } = await open(view)
      await userEvent.click(within(row).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('region', { name: region })).toHaveTextContent(text)
      cleanup()
    }
  })
})
