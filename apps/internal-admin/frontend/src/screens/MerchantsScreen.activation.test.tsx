import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * MERCHANT-OPS-002 §8.3 B3-1 9 — 조회 상세 MID 아래 "개통" 영역(§9.17): 결제수단(등록자, "개통 등록(카드)" 1인 → "개통 승인 요청" 2인 — 근거 라벨 "기관(카드사/VAN) 회신 참조"),
 * Site 심사(등록자, "심사 승인 요청" 2인 — "PG 심사 근거", "심사 거절" 1인 — 사유 선택·확인 대화상자 "되돌릴 수 없습니다. 재신청은 새 Site로"), Site 적용 확인(유효/철회),
 * 개통 점검표("참고용 — 실제 판정은 결제 시점 PAYMENT"). 버튼은 OPERATOR 표시만(서버 권위). 같은 그룹 대기면 그 그룹 2인 버튼만 숨기고 1인 거절은 숨기지 않는다(C-35).
 * Demo 식별자, 기준 시각 2026-11-11T03:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MID1 = id('mid', 1)
const ACT1 = id('act', 1)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const SITE_REGISTRANT = 'admin:adm_01JCSR0000000000000000SR01'
const NOW = () => new Date('2026-11-11T03:00:00.000Z')
const APR = 'apr_01JC0000000000000000000B31'
const SUB = 'b'.repeat(32)

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 3, midCount: 1, createdAt: '2026-11-01T00:00:00.000Z',
}
type Account = MerchantDetail['accounts'][number]
const account = (patch: Partial<Account> = {}): Account => ({
  merchantAccountId: MID1, lifecycle: 'ACTIVE', isDefault: true, issuingContractId: id('ctr', 1), issuingAgreementId: id('csa', 1), issuingRevisionNo: 1,
  issuedAt: '2026-11-10T00:00:00.000Z',
  assignments: [{ assignmentId: id('asg', 1), merchantSiteId: SITE1, role: 'PRIMARY', status: 'ACTIVE', effectiveFrom: '2026-11-10T00:00:00.000Z', effectiveTo: null }],
  paymentMethods: [], currencies: [], cardRoutes: [], siteConfirmations: [], ...patch,
})
/** Named Fixture: B2-3까지 끝난 가맹점(오늘 적용 Revision 승인·MID·PRIMARY Site1 심사 결정 없음) + 두 번째 Site2 심사 결정 없음. */
function detail(patch: Partial<MerchantDetail> = {}, accountPatch: Partial<Account> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 3, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: SITE_REGISTRANT },
      { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'second.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: SITE_REGISTRANT },
    ],
    contracts: [{
      contractId: id('ctr', 1), createdAt: '2026-11-01T00:00:00.000Z',
      revisions: [{ revisionNo: 1, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-10T16:00:00.000Z', scopeSiteIds: [], registeredBy: 'admin:adm_X' }],
      agreements: [{ agreementId: id('csa', 1), serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [account(accountPatch)], limits: [],
    propagation: { latestRevision: 3, latestRevisionIssuedAt: '2026-11-11T00:00:00.000Z', latestAckRevision: 3, latestAckAt: '2026-11-11T00:00:01.000Z', effectConfirmedAt: '2026-11-11T00:00:01.000Z', effectBasis: 'ACK' },
    pendingApprovals: [],
    ...patch,
  }
}
const underReview = { activationId: ACT1, paymentMethod: 'CARD', status: 'UNDER_REVIEW', registeredAt: '2026-11-11T01:00:00.000Z', registeredBy: SITE_REGISTRANT }

async function openDetail(canRequestApproval: boolean, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail()),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} now={NOW} newSubmissionId={() => SUB} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  return { api, area: await screen.findByRole('region', { name: `개통 ${MID1}` }) }
}

/**
 * 기대값 변경(MERCHANT-IA-001 B1 — 명세 rev 0.2 §7 B1, D-ADMIN-UI-010 "한 사실은 한 곳에"): Site 심사 표는 MID "개통" 영역마다 반복하지 않고 가맹점에서 한 번(Site 패널 안)
 * 그린다. 그래서 Site 심사 단언은 개통 영역(area) 대신 이 표에서 찾는다 — 버튼·등록자·잠금 단언은 그대로.
 */
const siteReviewTable = () => screen.getByRole('table', { name: 'Site 심사' })

describe('MerchantsScreen — B3-1 "개통" area (MERCHANT-OPS-002 §9.17)', () => {
  it('a VIEWER sees statuses, registrants, confirmations and the checklist but no button', async () => {
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => detail({}, { paymentMethods: [underReview] })) })
    expect(within(within(area).getByRole('table', { name: '결제수단 개통' })).getByText(SITE_REGISTRANT)).toBeInTheDocument()
    expect(siteReviewTable()).toHaveTextContent(SITE2)
    for (const name of ['개통 등록(카드)', '개통 승인 요청', '심사 승인 요청', '심사 거절']) expect(within(area).queryByRole('button', { name })).toBeNull()
    for (const name of ['심사 승인 요청', '심사 거절']) expect(within(siteReviewTable()).queryByRole('button', { name })).toBeNull()
    expect(within(area).getByRole('region', { name: '개통 점검표' })).toBeInTheDocument()
  })

  it('OPERATOR "개통 등록(카드)" (1인) sends {submissionId, merchantAccountId, CARD}, shows the act_ result and re-reads the detail; hidden once a card activation exists', async () => {
    const registerPaymentMethodActivation = vi.fn(async () => ({ outcome: 'SUCCEEDED' as const, reference: ACT1, replayed: false }))
    const merchantDetail = vi.fn().mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail({}, { paymentMethods: [underReview] }))
    const { area } = await openDetail(true, { registerPaymentMethodActivation, merchantDetail })
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    expect(registerPaymentMethodActivation).toHaveBeenCalledWith({ submissionId: SUB, merchantAccountId: MID1, paymentMethod: 'CARD' })
    expect(merchantDetail).toHaveBeenCalledTimes(2)
    const fresh = await screen.findByRole('region', { name: `개통 ${MID1}` })
    expect(within(fresh).queryByRole('button', { name: '개통 등록(카드)' })).toBeNull()
    expect(within(fresh).getByRole('button', { name: '개통 승인 요청' })).toBeInTheDocument()
  })

  it('an unknown outcome keeps the same submissionId for the resend; a rejection shows its message', async () => {
    const registerPaymentMethodActivation = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce({ outcome: 'REJECTED', rejection: 'ACTIVATION_DUPLICATE', replayed: true })
    let n = 0
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })), merchantDetail: vi.fn(async () => detail()),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })), registerPaymentMethodActivation,
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval now={NOW} newSubmissionId={() => `${++n}`.padStart(32, 'c')} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    const area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    await userEvent.click(await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    expect(registerPaymentMethodActivation.mock.calls.map((c) => c[0].submissionId)).toEqual(['1'.padStart(32, 'c'), '1'.padStart(32, 'c')])
    expect(await within(area).findByText('이미 개통 등록이 있습니다')).toBeInTheDocument()
  })

  it('§4.2·§5.3: while resending an unknown outcome, a non-409 error (503 MASTER_BUSY, 429) keeps the lock and the same submissionId; only 409 ends it', async () => {
    const registerPaymentMethodActivation = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_BUSY'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: ACT1, replayed: false })
    let n = 0
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })), merchantDetail: vi.fn(async () => detail()),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })), registerPaymentMethodActivation,
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval now={NOW} newSubmissionId={() => `${++n}`.padStart(32, 'c')} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    const area = await screen.findByRole('region', { name: `개통 ${MID1}` })
    await userEvent.click(within(area).getByRole('button', { name: '개통 등록(카드)' }))
    await userEvent.click(await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    await userEvent.click(await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    await userEvent.click(await within(area).findByRole('button', { name: '같은 개통 등록 다시 보내기' }))
    // 409(결함 신호)만 잠금을 푼다 — 다음 제출은 새 submissionId.
    await userEvent.click(await within(area).findByRole('button', { name: '개통 등록(카드)' }))
    const one = '1'.padStart(32, 'c')
    expect(registerPaymentMethodActivation.mock.calls.map((c) => c[0].submissionId)).toEqual([one, one, one, one, '2'.padStart(32, 'c')])
  })

  it('"개통 승인 요청" (2인) needs the institution reply reference, sends it, then shows the result; hidden while a PM-group approval is pending', async () => {
    const requestMerchantPaymentMethodActivation = vi.fn(async () => ({ approvalId: APR }))
    const { area } = await openDetail(true, { requestMerchantPaymentMethodActivation, merchantDetail: vi.fn(async () => detail({}, { paymentMethods: [underReview] })) })
    await userEvent.click(within(area).getByRole('button', { name: '개통 승인 요청' }))
    const form = within(area).getByRole('form', { name: `개통 승인 요청 ${ACT1}` })
    const send = within(form).getByRole('button', { name: '승인 요청 보내기' })
    expect(send).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('기관(카드사/VAN) 회신 참조(필수)'), 'VAN-REPLY/2026-0001')
    await userEvent.click(send)
    expect(requestMerchantPaymentMethodActivation).toHaveBeenCalledWith({ merchantId: MER1, merchantAccountId: MID1, activationId: ACT1, evidenceRef: 'VAN-REPLY/2026-0001' })
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('결제수단 개통 승인')
    expect(storageIsEmpty()).toBe(true)
  })

  it('C-35: a pending activation hides "개통 승인 요청" but not "심사 승인 요청"; a pending site review hides "심사 승인 요청" but never "심사 거절"', async () => {
    const pm = detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_PAYMENT_METHOD_ACTIVATION', status: 'PENDING', revisionNo: null }] }, { paymentMethods: [underReview] })
    const { area } = await openDetail(true, { merchantDetail: vi.fn(async () => pm) })
    expect(within(area).queryByRole('button', { name: '개통 승인 요청' })).toBeNull()
    expect(within(siteReviewTable()).getAllByRole('button', { name: '심사 승인 요청' })).toHaveLength(2)
  })

  it.each(['MERCHANT_PAYMENT_METHOD_ACTIVATION', 'MERCHANT_SITE_REVIEW_APPROVAL'])(
    'review m-2 (C-35): a pending %s (B3 group) does not hide the B2 "승인 요청" (contract approval) or "첫 MID 발급 요청" buttons',
    async (kind) => {
      // MID 0개(첫 MID 발급 조건) + 승인 Revision 1 + 미승인·끝나지 않은 Revision 2(계약 승인 요청 조건). B3 그룹 대기는 B2 그룹 버튼과 무관하다.
      const base = detail()
      const contract = base.contracts[0]!
      const d = detail({
        accounts: [],
        contracts: [{
          ...contract,
          revisions: [
            ...contract.revisions,
            { revisionNo: 2, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-12T15:00:00.000Z', effectiveTo: null, approvedAt: null, scopeSiteIds: [], registeredBy: 'admin:adm_X' },
          ],
        }],
        pendingApprovals: [{ approvalId: APR, kind, status: 'PENDING', revisionNo: null }],
      })
      const api = fakeApi({
        merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
        merchantDetail: vi.fn(async () => d),
        merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
      })
      render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={true} now={NOW} newSubmissionId={() => SUB} />)
      await userEvent.click(screen.getByRole('button', { name: '검색' }))
      await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
      expect(await screen.findByRole('button', { name: '승인 요청' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '첫 MID 발급 요청' })).toBeInTheDocument()
    },
  )

  it('C-35: with a pending site review the 2-person button is hidden for every site while the 1-person reject stays', async () => {
    const sr = detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_SITE_REVIEW_APPROVAL', status: 'EXECUTION_UNKNOWN', revisionNo: null }] }, { paymentMethods: [underReview] })
    const { area } = await openDetail(true, { merchantDetail: vi.fn(async () => sr) })
    expect(within(siteReviewTable()).queryByRole('button', { name: '심사 승인 요청' })).toBeNull()
    expect(within(siteReviewTable()).getAllByRole('button', { name: '심사 거절' })).toHaveLength(2)
    expect(within(area).getByRole('button', { name: '개통 승인 요청' })).toBeInTheDocument()
  })

  it('"심사 승인 요청" (2인) uses the PG review basis and REVIEW_PASSED; APPROVED or REJECTED sites have no buttons', async () => {
    const requestMerchantSiteReviewApproval = vi.fn(async () => ({ approvalId: APR }))
    const d = detail({
      sites: [
        { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-11-11T00:00:00.000Z' }, registeredBy: SITE_REGISTRANT },
        { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'second.example.test', lifecycle: 'ACTIVE', review: null, registeredBy: SITE_REGISTRANT },
        { merchantSiteId: id('site', 3), channelType: 'APP', channelIdentifier: 'com.example.app', lifecycle: 'ACTIVE', review: { decision: 'REJECTED', decidedAt: '2026-11-11T00:00:00.000Z' }, registeredBy: SITE_REGISTRANT },
      ],
    })
    await openDetail(true, { requestMerchantSiteReviewApproval, merchantDetail: vi.fn(async () => d) })
    const table = siteReviewTable()
    expect(within(within(table).getByRole('row', { name: `심사 ${SITE1}` })).queryAllByRole('button')).toHaveLength(0)
    expect(within(within(table).getByRole('row', { name: `심사 ${id('site', 3)}` })).queryAllByRole('button')).toHaveLength(0)
    const row = within(table).getByRole('row', { name: `심사 ${SITE2}` })
    await userEvent.click(within(row).getByRole('button', { name: '심사 승인 요청' }))
    await userEvent.type(within(row).getByLabelText('PG 심사 근거(필수)'), 'PG-REVIEW/2026-0001')
    await userEvent.click(within(row).getByRole('button', { name: '승인 요청 보내기' }))
    expect(requestMerchantSiteReviewApproval).toHaveBeenCalledWith({ merchantId: MER1, merchantSiteId: SITE2, reasonCode: 'REVIEW_PASSED', evidenceRef: 'PG-REVIEW/2026-0001' })
  })

  it('"심사 거절" (1인): reason select, confirmation dialog "되돌릴 수 없습니다. 재신청은 새 Site로", cancel sends nothing, confirm sends {submissionId, site, reason} without evidence', async () => {
    const rejectSiteReview = vi.fn(async () => ({ outcome: 'SUCCEEDED' as const, reference: `${SITE2}#1`, replayed: false }))
    await openDetail(true, { rejectSiteReview })
    const row = within(siteReviewTable()).getByRole('row', { name: `심사 ${SITE2}` })
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.selectOptions(within(row).getByLabelText('거절 사유'), 'SITE_UNREACHABLE')
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    const dialog = within(row).getByRole('alertdialog', { name: '심사 거절 확인' })
    expect(dialog).toHaveTextContent('되돌릴 수 없습니다. 재신청은 새 Site로')
    await userEvent.click(within(dialog).getByRole('button', { name: '취소' }))
    expect(rejectSiteReview).not.toHaveBeenCalled()
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.click(within(within(row).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    expect(rejectSiteReview).toHaveBeenCalledWith({ submissionId: SUB, merchantSiteId: SITE2, reasonCode: 'SITE_UNREACHABLE' })
  })

  it('§5.3: an unknown "심사 거절" locks the reason and evidence inputs and other sites\' reject; the resend carries the same submissionId and body', async () => {
    const rejectSiteReview = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_BUSY'))
      .mockResolvedValueOnce({ outcome: 'SUCCEEDED', reference: `${SITE2}#1`, replayed: true })
    let n = 0
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })), merchantDetail: vi.fn(async () => detail()),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })), rejectSiteReview,
    })
    // 리뷰 MINOR-2: 매번 다른 값을 내는 생성기 — 재전송이 새 submissionId를 만들면 아래 단언이 실패한다.
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval now={NOW} newSubmissionId={() => `${++n}`.padStart(32, 'd')} />)
    await userEvent.click(screen.getByRole('button', { name: '검색' }))
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    await screen.findByRole('region', { name: `개통 ${MID1}` })
    const table = siteReviewTable()
    const row = within(table).getByRole('row', { name: `심사 ${SITE2}` })
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.selectOptions(within(row).getByLabelText('거절 사유'), 'SITE_UNREACHABLE')
    await userEvent.click(within(row).getByRole('button', { name: '심사 거절' }))
    await userEvent.click(within(within(row).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    expect(await within(row).findByRole('button', { name: '같은 거절 다시 보내기' })).toBeInTheDocument()
    expect(within(row).getByLabelText('거절 사유')).toBeDisabled()
    expect(within(row).getByLabelText('PG 심사 근거(선택)')).toBeDisabled()
    // 리뷰 MINOR-1: 다른 Site 거절을 열면 이 폼이 사라지므로 결과 불명 동안 막는다.
    expect(within(within(table).getByRole('row', { name: `심사 ${SITE1}` })).getByRole('button', { name: '심사 거절' })).toBeDisabled()
    for (let i = 0; i < 2; i++) {
      await userEvent.click(await within(row).findByRole('button', { name: '같은 거절 다시 보내기' }))
      await userEvent.click(within(within(row).getByRole('alertdialog')).getByRole('button', { name: '거절 확정' }))
    }
    const one = '1'.padStart(32, 'd')
    expect(rejectSiteReview.mock.calls.map((c) => c[0])).toEqual(Array(3).fill({ submissionId: one, merchantSiteId: SITE2, reasonCode: 'SITE_UNREACHABLE' }))
    // 확정 성공 뒤에는 다른 Site 거절을 다시 열 수 있다(상세 재조회 뒤 다시 찾는다).
    await screen.findByRole('region', { name: `개통 ${MID1}` })
    const fresh = siteReviewTable()
    expect(await within(within(fresh).getByRole('row', { name: `심사 ${SITE1}` })).findByRole('button', { name: '심사 거절' })).toBeEnabled()
  })

  it('site confirmations list valid / revoked', async () => {
    const d = detail({}, {
      paymentMethods: [{ ...underReview, status: 'ACTIVE' }],
      siteConfirmations: [
        { confirmationId: id('sac', 1), merchantSiteId: SITE1, paymentMethod: 'CARD', activationId: ACT1, confirmedAt: '2026-11-11T02:00:00.000Z', revoked: false },
        { confirmationId: id('sac', 2), merchantSiteId: SITE2, paymentMethod: 'CARD', activationId: ACT1, confirmedAt: '2026-11-11T02:00:00.000Z', revoked: true },
      ],
    })
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => d) })
    const table = within(area).getByRole('table', { name: 'Site 적용 확인' })
    expect(within(table).getByRole('row', { name: id('sac', 1) })).toHaveTextContent('유효')
    expect(within(table).getByRole('row', { name: id('sac', 2) })).toHaveTextContent('철회')
  })

  it('checklist (C-43 — reference only): ①~④ met and ⑤ currency not met after B3-1, ⑦ "준비 전", ⑧ ACK; the "참고용" notice is shown', async () => {
    const d = detail({
      sites: [
        { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: { decision: 'APPROVED', decidedAt: '2026-11-11T02:30:00.000Z' }, registeredBy: SITE_REGISTRANT },
      ],
    }, {
      paymentMethods: [{ ...underReview, status: 'ACTIVE' }],
      siteConfirmations: [{ confirmationId: id('sac', 1), merchantSiteId: SITE1, paymentMethod: 'CARD', activationId: ACT1, confirmedAt: '2026-11-11T02:00:00.000Z', revoked: false }],
    })
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => d) })
    const list = within(area).getByRole('region', { name: '개통 점검표' })
    expect(list).toHaveTextContent('이 표는 참고용입니다. 실제 판정은 결제 시점에 PAYMENT가 합니다')
    const state = (label: RegExp) => within(list).getByRole('listitem', { name: label }).getAttribute('data-state')
    expect([state(/^① /), state(/^② /), state(/^③ /), state(/^④ /), state(/^⑤ /), state(/^⑥ /), state(/^⑦ /), state(/^⑧ /)]).toEqual(
      ['MET', 'MET', 'MET', 'MET', 'NOT_MET', 'NOT_MET', 'LATER', 'MET'],
    )
    // 기대값 변경(MERCHANT-IA-001 B1 — 명세 §3.6 "작업 배치 이름 → 준비 전"): ⑦ 표시 문구만 바뀌었다(상태 LATER 그대로).
    expect(within(list).getByRole('listitem', { name: /^⑦ / })).toHaveTextContent('준비 전')
    expect(within(list).getByRole('listitem', { name: /^⑦ / })).not.toHaveTextContent('B4')
  })

  it('a revoked PRIMARY confirmation or a pending propagation is not met', async () => {
    const d = detail({ propagation: { latestRevision: 4, latestRevisionIssuedAt: '2026-11-11T00:00:00.000Z', latestAckRevision: 3, latestAckAt: null, effectConfirmedAt: null, effectBasis: null } }, {
      paymentMethods: [{ ...underReview, status: 'ACTIVE' }],
      siteConfirmations: [{ confirmationId: id('sac', 1), merchantSiteId: SITE1, paymentMethod: 'CARD', activationId: ACT1, confirmedAt: '2026-11-11T02:00:00.000Z', revoked: true }],
    })
    const { area } = await openDetail(false, { merchantDetail: vi.fn(async () => d) })
    const list = within(area).getByRole('region', { name: '개통 점검표' })
    expect(within(list).getByRole('listitem', { name: /^④ / }).getAttribute('data-state')).toBe('NOT_MET')
    expect(within(list).getByRole('listitem', { name: /^⑧ / }).getAttribute('data-state')).toBe('NOT_MET')
  })
})
