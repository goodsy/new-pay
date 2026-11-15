import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { fakeApi } from '../test/fakeApi'
import { CODE_CHANGE_NOTICE } from '../components/MerchantCodeChangeRequest'

/**
 * MERCHANT-CODE-001 §8 MC-1b-9 — 가맹점 상세 "코드 변경 요청"(§5.5): 버튼은 OPERATOR이고 MASTER `merchantCodeChangeable`이며 같은 그룹(계약 승인·첫 MID·코드 변경) 대기가 없을 때만,
 * 대화상자 문구 "계약 승인 전 1회만, 옛 코드는 다시 쓸 수 없습니다", 새 코드 형식·현재 코드와 다름, 요청 본문(내부 키는 본문에만), "코드 이력"(은퇴 코드 표시), 등록자 안내 0건,
 * 화면 DOM 전체에 mer_ 0건(D-ADMIN-UI-004 ③). Demo 식별자.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const MER1 = id('mer', 1)
const NOW = () => new Date('2026-11-11T03:00:00.000Z')
const item: MerchantListItem = {
  merchantId: MER1, merchantCode: 'shopabcd12', merchantName: '데모 가맹점', businessId: id('biz', 1), businessName: '데모 사업자', registrationNumberMasked: '123*******',
  lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 0, createdAt: '2026-11-01T00:00:00.000Z',
}
const history1 = [{ code: 'shopabcd12', seq: 1, source: 'REGISTRATION', replacesCode: null, assignedAt: '2026-11-01T00:00:00.000Z', assignedBy: 'admin:adm_X', reasonCode: null }]
const history2 = [...history1, { code: 'shopnew2026', seq: 2, source: 'CHANGE', replacesCode: 'shopabcd12', assignedAt: '2026-11-02T00:00:00.000Z', assignedBy: 'admin:adm_Y', reasonCode: 'TYPO_CORRECTION' }]
function detail(patch: Partial<MerchantDetail> = {}): MerchantDetail {
  return {
    merchant: { merchantId: MER1, merchantCode: 'shopabcd12', name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 1, createdAt: '2026-11-01T00:00:00.000Z' },
    business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumberMasked: '123*******', lifecycle: 'ACTIVE' },
    sites: [], contracts: [], accounts: [], limits: [],
    propagation: { latestRevision: 1, latestRevisionIssuedAt: '2026-11-01T00:00:00.000Z', latestAckRevision: null, latestAckAt: null, effectConfirmedAt: null, effectBasis: null },
    pendingApprovals: [], merchantCodeHistory: history1, merchantCodeChangeable: true,
    ...patch,
  }
}

async function openDetail(canRequestApproval: boolean, d: MerchantDetail = detail(), overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({
    merchantSearch: vi.fn(async () => ({ items: [item], nextCursor: null })),
    merchantDetail: vi.fn(async () => d),
    merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    ...overrides,
  })
  const view = render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} canRequestApproval={canRequestApproval} now={NOW} newSubmissionId={() => 'c'.repeat(32)} />)
  await userEvent.click(screen.getByRole('button', { name: '검색' }))
  await userEvent.click(within(await screen.findByRole('row', { name: 'shopabcd12' })).getByRole('button', { name: '상세' }))
  await screen.findByRole('heading', { name: /가맹점 상세/ })
  return { api, view }
}

/** DOM 전체(텍스트·모든 속성·입력 value)에서 내부 키 접두를 찾는다. */
function domHasInternalKey(container: HTMLElement): boolean {
  if (container.innerHTML.includes('mer_')) return true
  return Array.from(container.querySelectorAll('input')).some((i) => i.value.includes('mer_'))
}

describe('MerchantsScreen — MC-1b code change request (MERCHANT-CODE-001 §5.5)', () => {
  it('OPERATOR + merchantCodeChangeable: "코드 변경 요청" opens the dialog with the notice; a VIEWER has no button', async () => {
    await openDetail(true)
    await userEvent.click(screen.getByRole('button', { name: '코드 변경 요청' }))
    const dialog = screen.getByRole('dialog', { name: '가맹점 코드 변경 요청' })
    expect(dialog).toHaveTextContent(CODE_CHANGE_NOTICE)
    expect(CODE_CHANGE_NOTICE).toBe('계약 승인 전 1회만 바꿀 수 있고, 옛 코드는 다시 쓸 수 없습니다.')
    expect(dialog).toHaveTextContent('현재 코드: shopabcd12')
    expect(dialog).not.toHaveTextContent('등록자')
  })

  it('no button: VIEWER; not changeable (after the first contract approval / already changed); a pending contract approval or code change in the same group', async () => {
    const cases: [boolean, MerchantDetail][] = [
      [false, detail()],
      [true, detail({ merchantCodeChangeable: false })],
      [true, detail({ merchantCodeChangeable: false, merchantCodeHistory: history2, merchant: { ...detail().merchant, merchantCode: 'shopnew2026' } })],
      [true, detail({ pendingApprovals: [{ approvalId: 'apr_1', kind: 'MERCHANT_CONTRACT_APPROVAL', status: 'PENDING', revisionNo: 1 }] })],
      [true, detail({ pendingApprovals: [{ approvalId: 'apr_2', kind: 'MERCHANT_CODE_CHANGE', status: 'PENDING', revisionNo: null }] })],
      [true, detail({ merchantCodeChangeable: undefined, merchantCodeHistory: undefined })],
    ]
    for (const [operator, d] of cases) {
      const { view } = await openDetail(operator, d)
      expect(screen.queryByRole('button', { name: '코드 변경 요청' })).not.toBeInTheDocument()
      view.unmount()
    }
  })

  it('the request body carries the internal key, new code, reason and evidence; invalid / same code cannot be sent; success shows the result', async () => {
    const requestMerchantCodeChange = vi.fn(async () => ({ approvalId: 'apr_01JC00000000000000000MC1B' }))
    await openDetail(true, detail(), { requestMerchantCodeChange })
    await userEvent.click(screen.getByRole('button', { name: '코드 변경 요청' }))
    const dialog = screen.getByRole('dialog', { name: '가맹점 코드 변경 요청' })
    const [, codeInput, evidenceInput] = within(dialog).getAllByRole('textbox')
    const send = within(dialog).getByRole('button', { name: '변경 요청 보내기' })
    await userEvent.type(evidenceInput!, 'MERCHANT-REQ/2026-0001')
    await userEvent.type(codeInput!, 'shopabcd12')
    expect(within(dialog).getByText('현재 코드와 같습니다.')).toBeInTheDocument()
    expect(send).toBeDisabled()
    await userEvent.clear(codeInput!)
    await userEvent.type(codeInput!, 'Shop-New')
    expect(send).toBeDisabled()
    await userEvent.clear(codeInput!)
    await userEvent.type(codeInput!, 'shopnew2026')
    await userEvent.selectOptions(within(dialog).getByRole('combobox'), 'MERCHANT_REQUEST')
    await userEvent.click(send)
    expect(requestMerchantCodeChange).toHaveBeenCalledWith({ merchantId: MER1, newCode: 'shopnew2026', reasonCode: 'MERCHANT_REQUEST', evidenceRef: 'MERCHANT-REQ/2026-0001' })
    expect(await screen.findByRole('status', { name: '승인 요청 결과' })).toHaveTextContent('가맹점 코드 변경')
  })

  it('"제안" fills a §3.1-shaped code from the English name preview (MC-2 library reuse)', async () => {
    await openDetail(true)
    await userEvent.click(screen.getByRole('button', { name: '코드 변경 요청' }))
    const dialog = screen.getByRole('dialog', { name: '가맹점 코드 변경 요청' })
    const [nameInput, codeInput] = within(dialog).getAllByRole('textbox')
    await userEvent.type(nameInput!, 'New Shop')
    await userEvent.click(within(dialog).getByRole('button', { name: '제안' }))
    expect((codeInput as HTMLInputElement).value).toMatch(/^newshop[a-z0-9]{4}$/)
  })

  it('"코드 이력" lists seq 1·2 with the retired code marked; the whole DOM has no mer_ (D-ADMIN-UI-004 ③)', async () => {
    const { view } = await openDetail(true, detail({ merchantCodeChangeable: false, merchantCodeHistory: history2, merchant: { ...detail().merchant, merchantCode: 'shopnew2026' } }))
    const panel = screen.getByRole('region', { name: '코드 이력' })
    const rows = within(panel).getAllByRole('listitem')
    expect(rows[0]).toHaveTextContent('shopabcd12')
    expect(rows[0]).toHaveTextContent('은퇴 — 다시 쓸 수 없음')
    expect(rows[1]).toHaveTextContent('shopnew2026')
    expect(rows[1]).toHaveTextContent('현재')
    expect(panel).toHaveTextContent('코드 변경 불가')
    expect(domHasInternalKey(view.container)).toBe(false)
  })

  it('with the request dialog open the DOM still has no mer_', async () => {
    const { view } = await openDetail(true)
    await userEvent.click(screen.getByRole('button', { name: '코드 변경 요청' }))
    expect(domHasInternalKey(view.container)).toBe(false)
  })
})
