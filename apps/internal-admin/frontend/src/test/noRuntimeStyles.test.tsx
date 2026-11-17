import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { OneTimeCode } from '../components/OneTimeCode'
import { ErrorScreen } from '../errors/ErrorScreen'
import { AccountsScreen } from '../screens/AccountsScreen'
import { AcquisitionsScreen } from '../screens/AcquisitionsScreen'
import { ApprovalsScreen } from '../screens/ApprovalsScreen'
import { AuditScreen } from '../screens/AuditScreen'
import { BusinessProfileFormScreen } from '../screens/BusinessProfileFormScreen'
import { BusinessesScreen } from '../screens/BusinessesScreen'
import { EnrollmentScreen } from '../screens/EnrollmentScreen'
import { LoginScreen } from '../screens/LoginScreen'
import { MerchantsScreen } from '../screens/MerchantsScreen'
import { PaymentOpsScreen } from '../screens/PaymentOpsScreen'
import { ReconciliationScreen } from '../screens/ReconciliationScreen'
import { TransactionsScreen } from '../screens/TransactionsScreen'
import type { ApprovalView, PaymentTargetView } from '../api/adminApi'
import { fakeApi } from './fakeApi'
import { HISTORY, individualRegisteredDetail, listItemOf } from './businessFixtures'
import { createScreenMemory } from '../lib/screenMemory'

/**
 * ADMIN-UI-001 CSP 회귀 방지(style-src 'self', 'unsafe-inline' 없음): 화면이 런타임에 <style> 요소·style 속성·외부 link/script를 만들지 않는다.
 * (react-remove-scroll 같은 주입 라이브러리나 inline style이 들어오면 실제 브라우저에서 CSP 위반이 된다.)
 */
function expectNoRuntimeStyles() {
  expect(document.querySelectorAll('style')).toHaveLength(0)
  expect(document.querySelectorAll('[style]')).toHaveLength(0)
  expect(document.querySelectorAll('link, script[src]')).toHaveLength(0)
}

const PAY = 'pay_01JC0000000000000000000001'
const pinned = { paymentId: PAY, paymentStatus: 'AUTHORIZED', currency: 'KRW', authorizedMinor: 10000, cancellableMinor: 7000, balanceRevision: 3, onHold: true }
const target: PaymentTargetView = { ...pinned, cancellable: true, inFlightCancel: false }
const ownerApproval: ApprovalView = {
  approvalId: 'apr_P', kind: 'OPERATOR_CORRECTION_CANCEL', targetKind: 'PAYMENT', targetActorId: null, targetRef: PAY, targetSnapshot: '{}', grantedRole: null,
  causeApprovalId: null, requestedBy: 'adm_R', requestedByDisplayName: '운영자', requestedAt: '2026-10-28T00:00:00Z', expiresAt: '2026-10-29T00:00:00Z', status: 'PENDING',
  decidedBy: null, decidedByDisplayName: null, decidedAt: null, resultCode: null,
  owner: {
    amountMinor: 7000, currency: 'KRW', purposeKind: 'INCIDENT_OR_RECON_CORRECTION', pinnedTarget: pinned, approvedAt: null, executeBefore: null, attemptCount: 1,
    nextAttemptAt: null, lastResponseAt: null, lastFailure: null, integrityFlag: 'AMOUNT', resendHaltedReason: null, ownerResultCode: null, ownerReference: null,
  },
}
const account = { actorId: 'adm_S', loginName: 'ops-s', displayName: '정지 S', status: 'ACTIVE', activeRoles: ['OPERATOR'], suspendedRoles: ['APPROVER'], pendingPurpose: null }

describe('ADMIN-UI-001: no runtime <style>, style attribute or external resource (admin CSP style-src self)', () => {
  it('login, enrollment, one-time code and error screens', () => {
    const login = render(<LoginScreen api={fakeApi()} onAuthenticated={vi.fn()} onEnroll={vi.fn()} notice="SESSION_EXPIRED" />)
    expect(screen.getByLabelText('로그인 이름')).toBeInTheDocument()
    expectNoRuntimeStyles()
    login.unmount()
    const enroll = render(<EnrollmentScreen api={fakeApi()} onDone={vi.fn()} />)
    expect(screen.getByLabelText('등록 코드')).toBeInTheDocument()
    expectNoRuntimeStyles()
    enroll.unmount()
    const code = render(<OneTimeCode code="ABCDEFGHIJKLMNOPQRSTUVWXYZ234567" onClose={vi.fn()} />)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expectNoRuntimeStyles()
    code.unmount()
    render(<ErrorScreen code="RETRY_LATER" onLogin={vi.fn()} onRetry={vi.fn()} />)
    expect(screen.getByRole('alert')).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('accounts with management actions and the reactivation cause select', async () => {
    const api = fakeApi({
      accounts: vi.fn(async () => ({ accounts: [account], truncated: true })),
      reactivationCauses: vi.fn(async () => ({ causes: [{ cause: 'apr_01RESET', roles: ['APPROVER'] }] })),
    })
    render(<AccountsScreen api={api} canManage />)
    const row = await screen.findByRole('row', { name: /ops-s/ })
    await userEvent.click(within(row).getByRole('button', { name: '재활성 원인 조회' }))
    expect(await within(row).findByLabelText('재활성 원인')).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('approvals with the owner-service detail and the pinned/current comparison', async () => {
    const api = fakeApi({
      approvals: vi.fn(async () => ({ approvals: [ownerApproval] })), approvalDetail: vi.fn(async () => ownerApproval),
      paymentTarget: vi.fn(async () => ({ ...target, balanceRevision: 4, inFlightCancel: true })),
      justification: vi.fn(async () => ({ approvalId: 'apr_P', purposeReference: 'RECON-2026-0001', reason: 'duplicate charge' })),
    })
    render(<ApprovalsScreen api={api} view="pending" canDecide viewer={{ actorId: 'adm_A', roles: ['APPROVER'] }} />)
    const row = await screen.findByRole('row', { name: /PENDING/ })
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    const panel = await screen.findByRole('region', { name: '소유 서비스 실행 상세' })
    expect(await within(panel).findByText('승인 시 무효(TARGET_CHANGED) — TOTP가 소비됩니다')).toBeInTheDocument()
    expectNoRuntimeStyles()
    // ADMIN-UI-WORKSPACE-001 승인함: 시안 구조(.tablewrap·.detailarea·카드)로 옮긴 뒤 결정 양식(인증 코드 입력)에 값을 넣고 근거·사유(.notice)를 연 상태에서도 style 속성·런타임 <style>이 없다.
    await userEvent.type(within(row).getByLabelText('인증 코드(6자리)'), '123456')
    await userEvent.click(within(panel).getByRole('button', { name: '근거·사유 보기' }))
    expect(await within(panel).findByText('RECON-2026-0001')).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('my requests (내 요청) with withdraw and an opened merchant approval detail', async () => {
    const mer = { merchantId: 'mer_x', merchantName: '데모 가맹점', merchantCode: 'demo01' }
    const codeChange: ApprovalView = {
      ...ownerApproval, approvalId: 'apr_MC', kind: 'MERCHANT_CODE_CHANGE', targetKind: 'MERCHANT', targetRef: null, owner: undefined,
      targetSnapshot: JSON.stringify({ merchantId: mer.merchantId, merchantName: mer.merchantName, currentCode: mer.merchantCode, newCode: 'demo02', reasonCode: 'TYPO_CORRECTION' }),
    }
    const api = fakeApi({ approvals: vi.fn(async () => ({ approvals: [codeChange] })), approvalDetail: vi.fn(async () => codeChange) })
    render(<ApprovalsScreen api={api} view="mine" canDecide={false} viewer={{ actorId: 'adm_R', roles: ['OPERATOR'] }} />)
    const row = await screen.findByRole('row', { name: /MERCHANT_CODE_CHANGE/ })
    expect(within(row).getByRole('button', { name: '철회' })).toBeInTheDocument()
    await userEvent.click(within(row).getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('region', { name: '가맹점 코드 변경 승인 상세' })).toHaveTextContent('demo01 → demo02')
    expectNoRuntimeStyles()
  })

  it('audit, payment correction cancel (with the request form) and reconciliation findings', async () => {
    const audit = render(<AuditScreen api={fakeApi({ audit: vi.fn(async () => ({ entries: [] })) })} />)
    expect(await screen.findByRole('button', { name: '조회' })).toBeInTheDocument()
    expectNoRuntimeStyles()
    audit.unmount()
    const ops = render(<PaymentOpsScreen api={fakeApi({ paymentTarget: vi.fn(async () => target) })} canRequest />)
    await userEvent.type(screen.getByLabelText('결제 ID'), PAY)
    await userEvent.click(screen.getByRole('button', { name: '대상 조회' }))
    expect(await screen.findByLabelText('사유')).toBeInTheDocument()
    expectNoRuntimeStyles()
    ops.unmount()
    render(<ReconciliationScreen api={fakeApi({
      reconciliation: vi.fn(async () => ({
        openCounts: { MISSING_DECISION: 1 }, openTotal: 1, truncated: true, lastRunAt: null, lastFullScanAt: null, lastResult: 'ITEM_ERROR',
        findings: [{ findingId: 1, kind: 'MISSING_DECISION', approvalId: 'apr_1', paymentRef: 'pay_1', detailCode: null, foundAt: '2026-10-28T00:00:00Z' }],
      })),
    })} />)
    expect(await screen.findByRole('heading', { name: '열린 대사 발견 1건' })).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('MERCHANT-OPS-001: merchant lookup with the registration number field and an empty result', async () => {
    render(<MerchantsScreen api={fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })} canSearchRegistrationNumber />)
    expect(screen.getByLabelText('사업자등록번호')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByText('조건에 맞는 가맹점이 없습니다.')).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('TXN-OPS-001: transaction lookup with a listed row', async () => {
    const item = {
      paymentId: PAY, environment: 'TEST', merchantAccountId: 'mid-demo-1', merchantOrderId: 'order-77', orderNamePreview: '주문', orderNameTruncated: false,
      amountMinor: 10000, currency: 'KRW', paymentMethod: 'CARD', status: 'AUTHORIZED', createdAt: '2026-11-01T00:00:00.000Z', merchant: { name: '데모상점', code: 'demoshop01' },
    }
    // ADMIN-UI-WORKSPACE-001 거래 조회: 시안 구조로 옮긴 상세(.detailarea — 카드·표·태그)까지 연 뒤에도 style 속성·런타임 <style>이 없다.
    const detail = {
      payment: { ...item, merchantSiteId: 'demo-site-001', orderName: '주문' },
      onHold: true, terminationEvidence: [],
      authorization: { authorizationId: 'pau_01JC0000000000000000000001', status: 'AUTHORIZED', failureEvidence: null, planKind: 'LUMP_SUM', installmentMonths: null, cardType: 'CREDIT', openedAt: '2026-11-01T00:00:01.000Z' },
      transactions: [], cancellations: [], returnBalance: null,
    }
    render(<TransactionsScreen api={fakeApi({ transactionSearch: vi.fn(async () => ({ items: [item], nextCursor: null, merchantLabels: 'OK' as const })), transactionDetail: vi.fn(async () => detail) })} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByRole('row', { name: PAY })).toBeInTheDocument()
    expectNoRuntimeStyles()
    await userEvent.click(screen.getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('region', { name: '반환 잔액' })).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('ACQ-VIEW-001: acquisition status with a listed file and its badges', async () => {
    const item = {
      fileId: 'ACQ.SFTPV1.TEST.20261020.001', acquirerCode: 'SFTPV1', environment: 'TEST', businessDate: '2026-10-20', fileVersion: 1, originalFileId: null,
      recordCount: 1, saleCount: 1, saleAmountMinor: 10000, cancelCount: 0, cancelAmountMinor: 0, netAmountMinor: 10000, currency: 'KRW',
      transmissionState: 'TRANSMITTED', receiptState: 'RECEIVED', resultState: 'AWAITING', sealedAt: '2026-10-20T14:05:00.000Z', transmittedAt: '2026-10-20T14:06:00.000Z',
      recordStatusCounts: { awaiting: 1, accepted: 0, rejected: 0, fileRejected: 0, unknown: 0, conflict: 0 }, attention: ['RESULT_OVERDUE'], reacquisitionCandidate: true, reacquisitionInProgress: false,
    }
    // ADMIN-UI-WORKSPACE-001 매입·대사: 시안 구조로 옮긴 상세(.detailarea — 카드·표·태그·.rolebar)와 재매입 요청 카드(OPERATOR)까지 연 뒤에도 style 속성·런타임 <style>이 없다.
    const rejectedRecord = {
      seq: 1, recordType: 'SALE', transactionId: 'ptx_ex1', originalTransactionId: null, merchantAccountId: 'demo-mid-001', institutionTransactionDate: '2026-10-20',
      eventAt: '2026-10-20T01:00:00.000Z', currency: 'KRW', approvedAmountMinor: 10000, nettedCancelAmountMinor: 0, recordAmountMinor: 10000, generation: 0, reacquisitionOf: null,
      status: 'REJECTED', results: [{ entryNo: 1, result: 'REJECTED', observedResult: null, reasonCode: '05', source: 'RESULT_FILE', responseRef: { kind: 'RESULT', sequenceNo: 1 }, recognizedAt: '2026-10-20T15:00:00.000Z' }],
    }
    const detail = {
      file: { ...item, cutoffPolicyVersion: 'acq-cutoff-demo-1', cutoffAt: '2026-10-20T14:00:00.000Z', contentSha256: 'a'.repeat(64), contentBytes: 100, transmissionLeaseUntil: null, updatedAt: '2026-10-20T15:00:00.000Z' },
      attempts: { total: 0, latest: [] }, responses: { total: 0, quarantined: 0, latest: [] },
      records: { items: [rejectedRecord], nextCursor: null }, readAt: '2026-10-21T00:00:00.000Z',
    }
    render(<AcquisitionsScreen canRequest api={fakeApi({ acquisitionSearch: vi.fn(async () => ({ items: [item], nextCursor: null })), acquisitionDetail: vi.fn(async () => detail) })} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByRole('row', { name: item.fileId })).toBeInTheDocument()
    expectNoRuntimeStyles()
    await userEvent.click(screen.getByRole('button', { name: '상세' }))
    expect(await screen.findByRole('region', { name: '재매입 요청' })).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('BUSINESS-PROFILE-001 BIZ-4-6: business registration form, business list, detail and history', async () => {
    const detail = individualRegisteredDetail()
    const form = render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'create' }} onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
    await userEvent.click(screen.getByRole('radio', { name: '법인' }))
    await userEvent.click(screen.getByRole('checkbox', { name: '실사업장이 사업장과 다름' }))
    expect(screen.getByLabelText('법인등록번호')).toBeInTheDocument()
    expectNoRuntimeStyles()
    form.unmount()
    const edit = render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'edit', detail }} onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
    await userEvent.clear(screen.getByLabelText('상호'))
    expect(screen.getByLabelText('변경 사유')).toBeInTheDocument()
    expectNoRuntimeStyles()
    edit.unmount()
    render(<BusinessesScreen api={fakeApi({
      searchBusinesses: vi.fn(async () => ({ items: [listItemOf(detail)], next: null })),
      businessDetail: vi.fn(async () => detail),
      businessHistory: vi.fn(async () => ({ items: HISTORY, nextAfter: null })),
    })} memory={createScreenMemory()} canEdit onContinue={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    expect(await screen.findByRole('region', { name: '사업자 상세' })).toBeInTheDocument()
    expectNoRuntimeStyles()
    await userEvent.click(screen.getByRole('tab', { name: '변경 이력' }))
    expect(await screen.findByRole('table', { name: '변경 이력' })).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('the signed-in console layout with the side menu', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_S', displayName: '보안 관리자', roles: ['SECURITY_ADMIN', 'APPROVER'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
    })
    render(<App api={api} />)
    expect(await screen.findByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expectNoRuntimeStyles()
  })
})

describe('ADMIN-UI-001: the stylesheet is self-contained (no CDN, no remote font)', () => {
  const css = readFileSync('src/styles.css', 'utf8') // Vitest 작업 디렉터리 = frontend/


  it('imports only tailwindcss and references no remote URL or font file', () => {
    const imports = [...css.matchAll(/@import\s+([^;]+);/g)].map((m) => m[1]!.trim())
    expect(imports).toEqual(['"tailwindcss"'])
    expect(css).not.toMatch(/url\(/i)
    expect(css).not.toMatch(/@font-face/i)
    expect(css).not.toMatch(/https?:\/\//i)
  })
})
