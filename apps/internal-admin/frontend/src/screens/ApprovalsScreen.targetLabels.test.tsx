import { describe, expect, it, vi } from 'vitest'
import { cleanup, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ApprovalsScreen } from './ApprovalsScreen'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import type { AdminApi, ApprovalView, MerchantApprovalDetail, MerchantTargetLabelPage } from '../api/adminApi'

/**
 * MERCHANT-CODE-001 §8 MC-3-19 승인함·내 요청(§5.6.2 — D-ADMIN-UI-004 ③·D-MCODE-003): 가맹점 대상 기존 행(target_ref `mer_`)·새 행(target_ref 코드) 모두 "데모상점 · demoshop01",
 * 행 접근 이름 "<종류> 데모상점 · demoshop01 <상태>", 라벨 호출 = 고유 키 정렬 묶음(0개 미호출, 120개 → 50·50·20 순차 3회), UNAVAILABLE·429·네트워크 실패 → "—" + 안내 1줄
 * (오류 알림·세션 종료 없음, 승인 버튼 정상), 상세 5종(pinned 상호 + 요청 시 코드, 다르면 "→ 현재 <코드>", 기존 행은 현재 코드만), 모든 상태에서 DOM `mer_`·`biz_` 0건.
 * 명시 Fixture: 가맹점 `MER`(현재 데모상점 · demoshop01), 내부 키는 응답에만(화면 미표시 — D-BIZLOOKUP-001).
 */
const ulid = (n: number) => `01JC${String(n).padStart(22, '0')}`
const MER = `mer_${ulid(1)}`
const CTR = `ctr_${ulid(1)}`
const LABEL = '데모상점 · demoshop01'
const NOTE = '가맹점 정보를 불러오지 못했어요'

const contractPinned = (code?: string) => ({
  merchantId: MER, merchantName: '데모 가맹점', ...(code ? { merchantCode: code } : {}), contractId: CTR, revisionNo: 2, scopeKind: 'MERCHANT', scopeSiteIds: [],
  effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approved: false, registeredBy: 'admin:adm_RG',
})
const detailOf = (commandKind: string, pinnedTarget: unknown): MerchantApprovalDetail => ({
  commandKind, merchantId: MER, pinnedTarget, approvedAt: null, executeBefore: null, attemptCount: null, nextAttemptAt: null, lastResponseAt: null,
  lastFailure: null, integrityFlag: null, resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
})
const approval = (id: string, targetRef: string, pinned: object, kind = 'MERCHANT_CONTRACT_APPROVAL', commandKind = 'CONTRACT_APPROVAL', merchantId = MER): ApprovalView => ({
  approvalId: id, kind, targetKind: 'MERCHANT', targetActorId: null, targetRef, targetMerchantId: merchantId, targetSnapshot: JSON.stringify(pinned),
  grantedRole: null, causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z',
  status: 'PENDING', decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null, merchant: detailOf(commandKind, pinned),
})
/** MC-3a 전 행: target_ref `mer_`, pinned 코드 없음. */
const legacyRow = approval('apr_OLD', MER, contractPinned())
/** MC-3a 뒤 행: target_ref = 요청 시점 코드, pinned `merchantCode`. */
const newRow = approval('apr_NEW', 'demoshop01', contractPinned('demoshop01'))
const accountRow: ApprovalView = {
  approvalId: 'apr_ACC', kind: 'ROLE_GRANT', targetKind: 'ADMIN_ACCOUNT', targetActorId: 'adm_T', targetRef: null, targetMerchantId: null, targetSnapshot: '{}', grantedRole: 'VIEWER',
  causeApprovalId: null, requestedBy: 'adm_REQ', requestedByDisplayName: '운영자', requestedAt: '2026-11-10T00:00:00Z', expiresAt: '2026-11-11T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
}
const okLabels = (labels: Record<string, [string, string]>) =>
  vi.fn(async (ids: string[]): Promise<MerchantTargetLabelPage> => ({
    items: ids.filter((id) => labels[id]).map((id) => ({ merchantId: id, name: labels[id]![0], code: labels[id]![1] })), labels: 'OK',
  }))

function renderScreen(rows: ApprovalView[], extra: Partial<AdminApi> = {}, view: 'pending' | 'mine' = 'pending') {
  const onSessionEnded = vi.fn()
  const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: rows })), approvalDetail: vi.fn(async (id: string) => rows.find((r) => r.approvalId === id)!), ...extra })
  render(<ApprovalsScreen api={api} view={view} canDecide={view === 'pending'} viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} onSessionEnded={onSessionEnded} />)
  return { api, onSessionEnded }
}

const noInternalKeys = () => {
  expect(domOccurrences(document.body, 'mer_')).toEqual([])
  expect(domOccurrences(document.body, 'biz_')).toEqual([])
}

describe('MC-3-19 승인함·내 요청 — 가맹점 대상 칸·행 접근 이름', () => {
  it('legacy (target_ref mer_) and new (target_ref code) rows both read "데모상점 · demoshop01" — cell and accessible name; one sorted label call; no internal key anywhere', async () => {
    for (const view of ['pending', 'mine'] as const) {
      const merchantLabels = okLabels({ [MER]: ['데모상점', 'demoshop01'] })
      renderScreen([legacyRow, newRow, accountRow], { merchantLabels }, view)
      const merchantRows = await screen.findAllByRole('row', { name: `MERCHANT_CONTRACT_APPROVAL ${LABEL} PENDING` })
      expect(merchantRows).toHaveLength(2)
      for (const row of merchantRows) expect(within(row).getByText(LABEL)).toBeInTheDocument()
      expect(screen.getByRole('row', { name: 'ROLE_GRANT adm_T PENDING' })).toBeInTheDocument()
      expect(merchantLabels).toHaveBeenCalledTimes(1)
      expect(merchantLabels).toHaveBeenCalledWith([MER])
      expect(screen.queryByText(NOTE)).toBeNull()
      noInternalKeys()
      cleanup()
    }
  })

  it('no merchant target → the label API is not called', async () => {
    const merchantLabels = okLabels({})
    renderScreen([accountRow], { merchantLabels })
    await screen.findByRole('row', { name: 'ROLE_GRANT adm_T PENDING' })
    expect(merchantLabels).not.toHaveBeenCalled()
  })

  it('120 distinct merchants → 50·50·20 sorted batches, called one after another (never concurrently)', async () => {
    const ids = Array.from({ length: 120 }, (_, i) => `mer_${ulid(500 + i)}`)
    const rows = [...ids].reverse().map((id, i) => approval(`apr_${i}`, id, contractPinned(), undefined, undefined, id))
    let inFlight = 0
    let maxInFlight = 0
    const merchantLabels = vi.fn(async (batch: string[]): Promise<MerchantTargetLabelPage> => {
      inFlight += 1
      maxInFlight = Math.max(maxInFlight, inFlight)
      await new Promise((resolve) => setTimeout(resolve, 5))
      inFlight -= 1
      return { items: batch.map((id) => ({ merchantId: id, name: '상점', code: `shop${id.slice(-6)}` })), labels: 'OK' }
    })
    renderScreen(rows, { merchantLabels })
    // 120행 렌더 + 순차 3회 — 전체 시험 동시 실행 부하에서도 기다리도록 findBy 대기 상한만 늘린다(기대값 불변).
    expect(await screen.findByRole('row', { name: `MERCHANT_CONTRACT_APPROVAL 상점 · shop${ids[119]!.slice(-6)} PENDING` }, { timeout: 10000 })).toBeInTheDocument()
    const sorted = [...ids].sort()
    expect(merchantLabels.mock.calls.map(([batch]) => batch)).toEqual([sorted.slice(0, 50), sorted.slice(50, 100), sorted.slice(100)])
    expect(maxInFlight).toBe(1)
    noInternalKeys()
  }, 20000)

  it('OK but the key is absent → "가맹점 정보 없음" (no note line)', async () => {
    renderScreen([legacyRow], { merchantLabels: okLabels({}) })
    expect(await screen.findByRole('row', { name: 'MERCHANT_CONTRACT_APPROVAL 가맹점 정보 없음 PENDING' })).toBeInTheDocument()
    expect(screen.queryByText(NOTE)).toBeNull()
    noInternalKeys()
  })

  it('UNAVAILABLE, 429 RATE_LIMITED and a network failure all show "—" + one note line; no error alert, no session ending, the approve flow still works', async () => {
    for (const merchantLabels of [
      vi.fn(async (): Promise<MerchantTargetLabelPage> => ({ items: [], labels: 'UNAVAILABLE' })),
      vi.fn(async (): Promise<MerchantTargetLabelPage> => { throw apiError(429, 'RATE_LIMITED') }),
      vi.fn(async (): Promise<MerchantTargetLabelPage> => { throw new TypeError('Failed to fetch') }),
    ]) {
      const approve = vi.fn(async () => ({ approvalId: 'apr_NEW', status: 'EXECUTING' }))
      const { onSessionEnded } = renderScreen([legacyRow, newRow], { merchantLabels, approve })
      expect(await screen.findByText(NOTE)).toHaveAttribute('role', 'note')
      expect(screen.getAllByText(NOTE)).toHaveLength(1)
      const rows = screen.getAllByRole('row', { name: 'MERCHANT_CONTRACT_APPROVAL — PENDING' })
      expect(rows).toHaveLength(2)
      expect(within(rows[1]!).getByText('—')).toBeInTheDocument()
      expect(screen.queryByRole('alert')).toBeNull()
      expect(onSessionEnded).not.toHaveBeenCalled()
      noInternalKeys()
      await userEvent.type(within(rows[1]!).getByLabelText('인증 코드(6자리)'), '123456')
      await userEvent.click(within(rows[1]!).getByRole('button', { name: '승인' }))
      expect(approve).toHaveBeenCalledWith('apr_NEW', '123456')
      expect(screen.queryByRole('alert')).toBeNull()
      cleanup()
    }
  })

  it('while labels load the cell shows "—" without the note line', async () => {
    let release: (page: MerchantTargetLabelPage) => void = () => undefined
    const merchantLabels = vi.fn(() => new Promise<MerchantTargetLabelPage>((resolve) => { release = resolve }))
    renderScreen([legacyRow], { merchantLabels })
    expect(await screen.findByRole('row', { name: 'MERCHANT_CONTRACT_APPROVAL — PENDING' })).toBeInTheDocument()
    expect(screen.queryByText(NOTE)).toBeNull()
    noInternalKeys()
    release({ items: [{ merchantId: MER, name: '데모상점', code: 'demoshop01' }], labels: 'OK' })
    expect(await screen.findByRole('row', { name: `MERCHANT_CONTRACT_APPROVAL ${LABEL} PENDING` })).toBeInTheDocument()
  })
})

describe('MC-3-19 승인 상세 5종 — 요청 시 코드 → 현재 코드', () => {
  const MID = `mid_${ulid(1)}`
  const SITE = `site_${ulid(1)}`
  const kinds: [string, string, (code?: string) => object, string][] = [
    ['MERCHANT_CONTRACT_APPROVAL', 'CONTRACT_APPROVAL', contractPinned, '가맹점 계약 승인 상세'],
    ['MERCHANT_FIRST_MID_ISSUANCE', 'FIRST_MID_ISSUANCE', (code) => ({
      merchantId: MER, merchantName: '데모 가맹점', ...(code ? { merchantCode: code } : {}), accountCount: 0, contractId: CTR, agreementId: `csa_${ulid(1)}`, revisionNo: 1,
      primarySiteId: SITE, channelType: 'WEB',
    }), '첫 MID 발급 승인 상세'],
    ['MERCHANT_PAYMENT_METHOD_ACTIVATION', 'PAYMENT_METHOD_ACTIVATION', (code) => ({
      merchantId: MER, merchantName: '데모 가맹점', ...(code ? { merchantCode: code } : {}), merchantAccountId: MID, activationId: `act_${ulid(1)}`, paymentMethod: 'CARD',
      status: 'UNDER_REVIEW', primarySiteId: SITE, registeredBy: 'admin:adm_RG',
    }), '결제수단 개통 승인 상세'],
    ['MERCHANT_SITE_REVIEW_APPROVAL', 'SITE_REVIEW_APPROVAL', (code) => ({
      merchantId: MER, merchantName: '데모 가맹점', ...(code ? { merchantCode: code } : {}), merchantSiteId: SITE, channelType: 'WEB', reviewStatus: 'PENDING',
      reasonCode: 'REVIEW_PASSED', registeredBy: 'admin:adm_RG',
    }), 'Site 심사 승인 상세'],
    ['MERCHANT_CURRENCY_ENABLEMENT', 'CURRENCY_ENABLEMENT', (code) => ({
      merchantId: MER, merchantName: '데모 가맹점', ...(code ? { merchantCode: code } : {}), merchantAccountId: MID, accountLifecycle: 'ACTIVE', currency: 'KRW',
      effectiveFromMode: 'AT_EXECUTION', effectiveFrom: null, effectiveTo: null,
    }), '통화 활성화 승인 상세'],
  ]

  async function openDetail(row: ApprovalView, labels: Record<string, [string, string]>) {
    renderScreen([row], { merchantLabels: okLabels(labels) })
    const tr = await screen.findByRole('row', { name: new RegExp(`^${row.kind} `) })
    await userEvent.click(within(tr).getByRole('button', { name: '상세' }))
    return tr
  }

  it('new row: "가맹점: <pinned 상호> · <요청 시 코드>", and "→ 현재 <코드>" when the current code differs', async () => {
    for (const [kind, commandKind, pinnedOf, region] of kinds) {
      await openDetail(approval(`apr_${kind}`, 'oldcode01', pinnedOf('oldcode01'), kind, commandKind), { [MER]: ['데모상점', 'newcode01'] })
      const panel = await screen.findByRole('region', { name: region })
      expect(panel).toHaveTextContent('가맹점: 데모 가맹점 · oldcode01 → 현재 newcode01')
      noInternalKeys()
      cleanup()
      await openDetail(approval(`apr_${kind}`, 'demoshop01', pinnedOf('demoshop01'), kind, commandKind), { [MER]: ['데모상점', 'demoshop01'] })
      const same = await screen.findByRole('region', { name: region })
      expect(same).toHaveTextContent('가맹점: 데모 가맹점 · demoshop01')
      expect(same).not.toHaveTextContent('→ 현재')
      cleanup()
    }
  })

  it('legacy row (no pinned code, target_ref mer_): current code only; labels unavailable → "—"; never the internal key', async () => {
    for (const [kind, commandKind, pinnedOf, region] of kinds) {
      await openDetail(approval(`apr_${kind}`, MER, pinnedOf(), kind, commandKind), { [MER]: ['데모상점', 'demoshop01'] })
      const panel = await screen.findByRole('region', { name: region })
      expect(panel).toHaveTextContent('가맹점: 데모 가맹점 · demoshop01')
      expect(panel).not.toHaveTextContent('→ 현재')
      noInternalKeys()
      cleanup()
      await openDetail(approval(`apr_${kind}`, MER, pinnedOf(), kind, commandKind), {})
      expect(await screen.findByRole('region', { name: region })).toHaveTextContent('가맹점: 데모 가맹점 · —')
      noInternalKeys()
      cleanup()
    }
  })

  it('new row whose target_ref is a code but pinned has no code (read tolerance) uses the target_ref as the request-time code', async () => {
    await openDetail(approval('apr_X', 'oldcode01', contractPinned()), { [MER]: ['데모상점', 'newcode01'] })
    expect(await screen.findByRole('region', { name: '가맹점 계약 승인 상세' })).toHaveTextContent('가맹점: 데모 가맹점 · oldcode01 → 현재 newcode01')
  })
})
