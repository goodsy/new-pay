import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi, storageIsEmpty } from '../test/fakeApi'

/**
 * MERCHANT-OPS-002 §8.2 B2-3-5 — 조회 상세 "첫 MID 발급 요청"(OPERATOR·MID 0개·승인된 Revision·CSA·ACTIVE Site일 때만, CSA·Revision·PRIMARY Site 선택, 근거 참조 필수),
 * Demo 개선 C-1(요청 결과가 상세를 다시 읽어도 한 번 남는다)·C-2(승인 대기 배너에 대상 Revision 또는 "첫 MID 발급"). Demo 식별자(업무 의미 없는 고정값), 기준 시각 2026-11-10T09:00Z.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
/** MC-2: 가맹점 코드 fixture(내부 키에서 결정적으로 — 화면은 mer_ 대신 이 코드를 보인다). */
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const CTR1 = id('ctr', 1)
const CSA1 = id('csa', 1)
const SITE1 = id('site', 1)
const SITE2 = id('site', 2)
const REGISTRANT = 'admin:adm_01JCRG0000000000000000RG01'
const NOW = () => new Date('2026-11-10T09:00:00.000Z')
const APR = 'apr_01JC0000000000000000000M09'

const item: MerchantListItem = {
  merchantId: MER1, merchantCode: codeOf(MER1), merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
type Revision = MerchantDetail['contracts'][number]['revisions'][number]
const revision = (n: number, patch: Partial<Revision> = {}): Revision => ({
  revisionNo: n, scopeKind: 'MERCHANT', effectiveFrom: '2026-11-08T15:00:00.000Z', effectiveTo: null, approvedAt: '2026-11-09T00:00:00.000Z', scopeSiteIds: [],
  registeredBy: REGISTRANT, ...patch,
})
/** Named Fixture: Revision 1 승인됨(지금 적용), Revision 2 승인 전, CSA 하나, Site 둘(하나는 종료 — PRIMARY 후보에서 빠진다), MID 없음. */
function detail(patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
    sites: [
      { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null },
      { merchantSiteId: SITE2, channelType: 'WEB', channelIdentifier: 'old.example.test', lifecycle: 'TERMINATED', review: null },
    ],
    contracts: [{
      contractId: CTR1, createdAt: '2026-11-01T00:00:00.000Z', revisions: [revision(1), revision(2, { approvedAt: null, effectiveFrom: '2026-11-11T15:00:00.000Z' })],
      agreements: [{ agreementId: CSA1, serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-01T00:00:00.000Z' }],
    }],
    accounts: [], limits: [], propagation: null, pendingApprovals: [],
    ...patch,
  }
}

async function openDetail(canRequestApproval: boolean, overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => detail()),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} now={NOW} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
  await screen.findByRole('region', { name: '계약·CSA' })
  return api
}

describe('MerchantsScreen — B2-3 first Default MID request (MERCHANT-OPS-002 §9.6)', () => {
  it('"첫 MID 발급 요청" only for an OPERATOR on a merchant without MID, with an approved revision, a CSA and an ACTIVE site', async () => {
    await openDetail(true)
    expect(screen.getByRole('button', { name: '첫 MID 발급 요청' })).toBeInTheDocument()
  })

  it('hidden without the OPERATOR flag, with an existing MID, without an approved revision, or while an approval is pending', async () => {
    await openDetail(false)
    expect(screen.queryByRole('button', { name: '첫 MID 발급 요청' })).toBeNull()
  })

  it.each([
    ['an existing MID', detail({ accounts: [{ merchantAccountId: id('mid', 1), lifecycle: 'ACTIVE', isDefault: true, issuingContractId: CTR1, issuingAgreementId: CSA1, issuingRevisionNo: 1, issuedAt: '2026-11-09T00:00:00.000Z', assignments: [], paymentMethods: [], currencies: [], cardRoutes: [] }] })],
    ['no approved revision', detail({ contracts: [{ ...detail().contracts[0]!, revisions: [revision(1, { approvedAt: null })] }] })],
    ['a pending approval', detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_CONTRACT_APPROVAL', status: 'PENDING', revisionNo: 2 }] })],
  ])('hidden with %s', async (_name, d) => {
    await openDetail(true, { merchantDetail: vi.fn(async () => d) })
    expect(screen.queryByRole('button', { name: '첫 MID 발급 요청' })).toBeNull()
  })

  it('the form offers approved revisions, CSAs and ACTIVE sites only; evidence is required; sends the five fields; the result stays after the detail is re-read (C-1)', async () => {
    const requestMerchantFirstMid = vi.fn(async () => ({ approvalId: APR }))
    const merchantDetail = vi.fn().mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_FIRST_MID_ISSUANCE', status: 'PENDING', revisionNo: null }] }))
    await openDetail(true, { requestMerchantFirstMid, merchantDetail })
    await userEvent.click(screen.getByRole('button', { name: '첫 MID 발급 요청' }))
    const form = screen.getByRole('form', { name: '첫 MID 발급 요청' })
    // 기대값 변경(B2-3 Demo 지적 3): 폼 제목, Revision 선택지에 계약 ID와 시작일(KST)을 함께 보인다.
    expect(within(form).getByRole('heading', { name: '첫 MID 발급 요청' })).toBeInTheDocument()
    // 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 선택지 글자·value에 계약·서비스 약정·Site 키가 없다(value는 목록 순번) — 고른 키는 보내는 본문에만(아래 호출 단언 그대로).
    expect(within(within(form).getByLabelText('Revision')).getAllByRole('option').map((o) => o.textContent)).toEqual(['계약 1 Revision 1 · 2026-11-09 시작'])
    expect(within(within(form).getByLabelText('PRIMARY Site')).getAllByRole('option').map((o) => [o.textContent, o.getAttribute('value')])).toEqual([['웹 · shop.example.test', '0']])
    expect(within(within(form).getByLabelText('서비스 계약(CSA)')).getAllByRole('option').map((o) => [o.textContent, o.getAttribute('value')])).toEqual([['서비스: 카드 PG 결제', '0']])
    const send = within(form).getByRole('button', { name: '발급 요청 보내기' })
    expect(send).toBeDisabled()
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'MID-2026/0001#p1')
    await userEvent.click(send)
    expect(requestMerchantFirstMid).toHaveBeenCalledWith({ merchantId: MER1, contractId: CTR1, agreementId: CSA1, revisionNo: 1, primarySiteId: SITE1, evidenceRef: 'MID-2026/0001#p1' })
    // C-1: 상세를 다시 읽은 뒤에도 결과가 패널 위에 남는다.
    // 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 결과 배너는 승인 ID 대신 무엇을 요청했는지(승인함에서 찾는다).
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('첫 MID 발급')
    expect(screen.getByRole('status', { name: '승인 요청 결과' })).not.toHaveTextContent(APR)
    // C-2: 승인 대기 배너가 대상(첫 MID 발급)을 말한다.
    const banner = await screen.findByRole('status', { name: '승인 대기' })
    // Demo 지적 1: 대상 표시와 종류 라벨이 겹쳐 "첫 MID 발급"이 두 번 보이지 않는다(한 번만).
    expect(banner.textContent?.split('첫 MID 발급').length).toBe(2)
    expect(merchantDetail).toHaveBeenCalledTimes(2)
    expect(storageIsEmpty()).toBe(true)
  })

  it('review m-3: with two contracts, changing the revision resets the CSA and site to the new options; a stale selection never enables the send button', async () => {
    const CTR2 = id('ctr', 2)
    const CSA2 = id('csa', 2)
    const two = detail({
      contracts: [
        detail().contracts[0]!,
        {
          contractId: CTR2, createdAt: '2026-11-02T00:00:00.000Z',
          revisions: [revision(1, { scopeKind: 'SITES', scopeSiteIds: [SITE2], effectiveFrom: '2026-11-09T15:00:00.000Z' })],
          agreements: [{ agreementId: CSA2, serviceKind: 'CARD_PG_PAYMENT', addedAt: '2026-11-02T00:00:00.000Z' }],
        },
      ],
      sites: [
        { merchantSiteId: SITE1, channelType: 'WEB', channelIdentifier: 'shop.example.test', lifecycle: 'ACTIVE', review: null },
        { merchantSiteId: SITE2, channelType: 'APP', channelIdentifier: 'app.example.test', lifecycle: 'ACTIVE', review: null },
      ],
    })
    const requestMerchantFirstMid = vi.fn(async () => ({ approvalId: APR }))
    await openDetail(true, { merchantDetail: vi.fn(async () => two), requestMerchantFirstMid })
    await userEvent.click(screen.getByRole('button', { name: '첫 MID 발급 요청' }))
    const form = screen.getByRole('form', { name: '첫 MID 발급 요청' })
    // 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 고른 값은 선택된 선택지의 사람용 글자로 본다(value는 목록 순번 — 키는 보내는 본문에만).
    expect((within(form).getByLabelText('서비스 계약(CSA)') as HTMLSelectElement).selectedOptions[0]?.textContent).toBe('서비스: 카드 PG 결제')
    expect((within(form).getByLabelText('PRIMARY Site') as HTMLSelectElement).selectedOptions[0]?.textContent).toBe('웹 · shop.example.test')
    await userEvent.selectOptions(within(form).getByLabelText('Revision'), '계약 2 Revision 1 · 2026-11-10 시작')
    expect((within(form).getByLabelText('Revision') as HTMLSelectElement).selectedOptions[0]?.textContent).toBe('계약 2 Revision 1 · 2026-11-10 시작')
    expect((within(form).getByLabelText('PRIMARY Site') as HTMLSelectElement).selectedOptions[0]?.textContent).toBe('앱 · app.example.test')
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'MID-2')
    await userEvent.click(within(form).getByRole('button', { name: '발급 요청 보내기' }))
    expect(requestMerchantFirstMid).toHaveBeenCalledWith({ merchantId: MER1, contractId: CTR2, agreementId: CSA2, revisionNo: 1, primarySiteId: SITE2, evidenceRef: 'MID-2' })
  })

  it('C-1/C-2 for the contract approval: the result stays after the re-read and the banner names the target revision', async () => {
    const merchantDetail = vi.fn().mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail({ pendingApprovals: [{ approvalId: APR, kind: 'MERCHANT_CONTRACT_APPROVAL', status: 'PENDING', revisionNo: 2 }] }))
    await openDetail(true, { requestMerchantContractApproval: vi.fn(async () => ({ approvalId: APR })), merchantDetail })
    await userEvent.click(within(screen.getByRole('row', { name: 'revision 2' })).getByRole('button', { name: '승인 요청' }))
    const form = screen.getByRole('form', { name: '승인 요청 revision 2' })
    await userEvent.type(within(form).getByLabelText('업무 근거 참조(필수)'), 'CONTRACT-1')
    await userEvent.click(within(form).getByRole('button', { name: '승인 요청 보내기' }))
    // 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 승인 ID 대신 대상 이름.
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('계약 승인(Revision 2)')
    expect(screen.getByRole('status', { name: '승인 요청 결과' })).not.toHaveTextContent(APR)
    expect(await screen.findByRole('status', { name: '승인 대기' })).toHaveTextContent('Revision 2')
  })
})
