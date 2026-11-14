import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { apiError, fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * MERCHANT-OPS-002 §8.2 B2-2-12 — 조회 상세 Revision 행 "승인 요청"(OPERATOR·승인 전·끝나지 않은 Revision만, 근거 참조 필수), 승인 대기 표시(C-24), Revision 등록자 표시(D-MOPS-008 ③).
 * Demo 식별자(업무 의미 없는 고정값). 기준 시각 2026-11-10T09:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const CTR1 = id('ctr', 1)
const REGISTRANT = 'admin:adm_01JCRG0000000000000000RG01'
const NOW = () => new Date('2026-11-10T09:00:00.000Z')

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE',
  entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
type Revision = MerchantDetail['contracts'][number]['revisions'][number]
const revision = (n: number, patch: Partial<Revision> = {}): Revision => ({
  revisionNo: n, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-10T15:00:00.000Z', effectiveTo: null, approvedAt: null, scopeSiteIds: [], registeredBy: REGISTRANT, ...patch,
})
/** Named Fixture: Revision 1 승인됨, 2 승인 전(요청 가능), 3 승인 전이지만 효력 끝남(요청 불가). */
const REVISIONS = [revision(1, { approvedAt: '2026-11-01T00:00:00.000Z' }), revision(2), revision(3, { effectiveFrom: '2026-10-01T15:00:00.000Z', effectiveTo: '2026-11-01T15:00:00.000Z' })]
function detail(pending: MerchantDetail['pendingApprovals'] = []): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [], contracts: [{ contractId: CTR1, createdAt: '2026-11-01T00:00:00.000Z', revisions: REVISIONS, agreements: [] }], accounts: [], limits: [], propagation: null,
    pendingApprovals: pending,
  }
}

async function openDetail(canRequestApproval: boolean, overrides: Parameters<typeof fakeApi>[0] = {}, onOpenApprovals?: () => void) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail()),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} onOpenApprovals={onOpenApprovals} now={NOW} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  await screen.findByRole('region', { name: '계약·CSA' })
  return api
}
const row = (n: number) => screen.getByRole('row', { name: `revision ${n}` })

describe('MerchantsScreen — B2-2 contract approval request (MERCHANT-OPS-002 §9.6)', () => {
  it('shows the revision registrant; "승인 요청" only for an OPERATOR on an unapproved, not-ended revision', async () => {
    await openDetail(true)
    expect(within(row(2)).getByText(REGISTRANT)).toBeInTheDocument()
    expect(within(row(2)).getByRole('button', { name: '승인 요청' })).toBeInTheDocument()
    expect(within(row(1)).queryByRole('button', { name: '승인 요청' })).toBeNull()
    expect(within(row(3)).queryByRole('button', { name: '승인 요청' })).toBeNull()
  })

  it('no button without the OPERATOR display flag', async () => {
    await openDetail(false)
    expect(screen.queryByRole('button', { name: '승인 요청' })).toBeNull()
    expect(within(row(2)).getByText(REGISTRANT)).toBeInTheDocument()
  })

  it('the evidence reference is required in its grammar; the request sends merchant, contract, revision and evidence, then re-reads the detail and shows "승인 대기 중"', async () => {
    const requested = vi.fn(async () => ({ approvalId: 'apr_01JC0000000000000000000M01' }))
    const merchantDetail = vi.fn().mockResolvedValueOnce(detail()).mockResolvedValueOnce(detail([{ approvalId: 'apr_01JC0000000000000000000M01', kind: 'MERCHANT_CONTRACT_APPROVAL', status: 'PENDING' }]))
    const toInbox = vi.fn()
    await openDetail(true, { requestMerchantContractApproval: requested, merchantDetail }, toInbox)
    await userEvent.click(within(row(2)).getByRole('button', { name: '승인 요청' }))
    const form = screen.getByRole('form', { name: '승인 요청 revision 2' })
    const send = within(form).getByRole('button', { name: '승인 요청 보내기' })
    expect(send).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'see contract')
    expect(send).toBeDisabled()
    expect(within(form).getByText(/형식이 맞지 않습니다/)).toBeInTheDocument()
    await userEvent.clear(within(form).getByLabelText('업무 근거 참조(필수)'))
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'CONTRACT-2026/0001#p1')
    await userEvent.click(send)
    expect(requested).toHaveBeenCalledWith({ merchantId: MER1, contractId: CTR1, revisionNo: 2, evidenceRef: 'CONTRACT-2026/0001#p1' })
    const banner = await screen.findByRole('status', { name: '승인 대기' })
    expect(banner).toHaveTextContent('승인 대기 중')
    expect(banner).toHaveTextContent('가맹점 계약 승인')
    // 승인 대기 중이면 요청 버튼을 숨긴다(C-24).
    expect(screen.queryByRole('button', { name: '승인 요청' })).toBeNull()
    expect(storageIsEmpty()).toBe(true)
  })

  it('server refusals use the merchant wording (duplicate unresolved, not eligible) and keep the form', async () => {
    await openDetail(true, { requestMerchantContractApproval: vi.fn(async () => { throw apiError(409, 'DUPLICATE_UNRESOLVED') }) })
    await userEvent.click(within(row(2)).getByRole('button', { name: '승인 요청' }))
    const form = screen.getByRole('form', { name: '승인 요청 revision 2' })
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'CONTRACT-1')
    await userEvent.click(within(form).getByRole('button', { name: '승인 요청 보내기' }))
    expect(await within(form).findByText(/이 가맹점에 아직 끝나지 않은 승인 요청이 있습니다/)).toBeInTheDocument()
  })
})
