import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import type { MerchantDetail, MerchantListItem } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { fakeApi } from '../test/fakeApi'

/**
 * MERCHANT-IA-001 B2(명세 §7 B2 — 사용자 결정 D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다", DB·API·타입 유지):
 * 가맹점 목록 '상태' 열과 '상태' 필터, 가맹점 상세 기본정보 '가맹점 상태'·'사업자 상태' 줄을 뺀다(머리 배지는 B4에서 판단).
 * 상태 필터가 빠져도 검색 호출에서 상태 조건만 빠지고 다른 조건·keyset '다음'은 그대로다. 예전에 화면 메모리에 남은 상태 필터 값은 무시한다.
 * Demo 식별자.
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const codeOf = (merchantId: string) => `shop${merchantId.slice(-6)}`
const MER1 = id('mer', 1)
const MER2 = id('mer', 2)
const MER3 = id('mer', 3)
const item = (merchantId: string, name: string): MerchantListItem => ({
  merchantId, merchantCode: codeOf(merchantId), merchantName: name, businessId: id('biz', 1), businessName: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE',
  entitlementRevision: 4, midCount: 1, createdAt: '2026-10-01T00:00:00.000Z',
})
const detail: MerchantDetail = {
  merchant: { merchantId: MER1, merchantCode: codeOf(MER1), name: '데모 가맹점', lifecycle: 'ACTIVE', entitlementRevision: 4, createdAt: '2026-10-01T00:00:00.000Z' },
  business: { businessId: id('biz', 1), name: '데모 사업자', registrationNumber: '1234567890', lifecycle: 'ACTIVE' },
  sites: [], contracts: [], accounts: [], limits: [], propagation: null, pendingApprovals: [],
}
const search = () => userEvent.click(screen.getByRole('button', { name: '검색' }))

describe('MERCHANT-IA-001 B2 — 가맹점 목록', () => {
  it('② 상태 필터와 상태 열이 없다; 빈 조건 검색은 {} (상태 조건 없음)', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    expect(screen.queryByLabelText('상태')).toBeNull()
    const form = screen.getByLabelText('가맹점명').closest('form')!
    // 기대값 변경(MERCHANT-IA-001 B6 — 사용자 결정 D-ADMIN-UI-011 ① 용어 통일, 명세 §7.7): 칸 이름은 보이는 label(필터 묶음 '항목명 | 입력')로 센다.
    expect(Array.from(form.querySelectorAll('input, select')).map((el) => (el as HTMLInputElement).labels?.[0]?.textContent ?? null)).toEqual(['가맹점 코드', '가맹점명', '사업자등록번호'])
    await search()
    expect(api.merchantSearch).toHaveBeenCalledWith({})
    const table = await screen.findByRole('table', { name: '가맹점 목록' })
    // 기대값 변경(MERCHANT-IA-001 B6 — 명세 §4.4): 사업자 칸 = "상호 · 번호" 한 칸, 작업 열 이름 '상세'.
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['가맹점', '사업자', 'MID 수', '등록일', '상세'])
    const row = screen.getByRole('row', { name: codeOf(MER1) })
    expect(row).not.toHaveTextContent('활성')
    expect(within(row).getAllByRole('cell')).toHaveLength(5)
  })

  it('② 예전 화면 메모리의 상태 필터 값(lookup.filter.lifecycle)은 무시한다 — 검색·다음 쪽 어디에도 lifecycle이 없다', async () => {
    const memory = createScreenMemory()
    memory.write('lookup.filter.lifecycle', 'TERMINATED')
    const api = fakeApi({
      merchantSearch: vi.fn()
        .mockResolvedValueOnce({ items: [item(MER1, '데모 하나'), item(MER2, '데모 둘')], nextCursor: MER2 })
        .mockResolvedValueOnce({ items: [item(MER3, '데모 셋')], nextCursor: null }),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} memory={memory} />)
    await userEvent.type(screen.getByLabelText('가맹점명'), '데모')
    await search()
    await screen.findByRole('row', { name: codeOf(MER2) })
    // 기대값 변경(MERCHANT-IA-001 B6 — 사용자 결정 D-ADMIN-UI-011 ① 용어 통일, 명세 §7.7): '다음' → '더 보기'(같은 호출).
    await userEvent.click(screen.getByRole('button', { name: '더 보기' }))
    expect(api.merchantSearch).toHaveBeenNthCalledWith(1, { displayNamePrefix: '데모' })
    expect(api.merchantSearch).toHaveBeenNthCalledWith(2, { displayNamePrefix: '데모', after: MER2 })
    expect(await screen.findByRole('row', { name: codeOf(MER3) })).toBeInTheDocument()
  })

  it('② 가맹점 코드는 여전히 단일 기준 — 함께 쓸 수 없는 조건 안내에 없는 "상태"를 말하지 않는다', async () => {
    const api = fakeApi({ merchantSearch: vi.fn(async () => ({ items: [], nextCursor: null })) })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await userEvent.type(screen.getByLabelText('가맹점 코드'), 'shopabcd12')
    await userEvent.type(screen.getByLabelText('가맹점명'), '데모')
    await search()
    expect(api.merchantSearch).not.toHaveBeenCalled()
    expect(screen.getByText('가맹점 코드 검색은 다른 조건(가맹점명·사업자등록번호)과 함께 쓸 수 없습니다.')).toBeInTheDocument()
  })
})

describe('MERCHANT-IA-001 B2 — 가맹점 상세', () => {
  it('② 기본정보에 가맹점 상태·사업자 상태 줄이 없다(가맹점·등록일·사업자·마스킹 번호는 그대로)', async () => {
    const api = fakeApi({
      merchantSearch: vi.fn(async () => ({ items: [item(MER1, '데모 가맹점')], nextCursor: null })),
      merchantDetail: vi.fn(async () => detail),
      merchantHistory: vi.fn(async () => ({ items: [], nextCursor: null })),
    })
    render(<MerchantsScreen api={api} canSearchRegistrationNumber={false} />)
    await search()
    await userEvent.click(within(await screen.findByRole('row', { name: codeOf(MER1) })).getByRole('button', { name: '상세' }))
    const basics = await screen.findByRole('region', { name: '기본정보' })
    expect(Array.from(basics.querySelectorAll('dt')).map((dt) => dt.textContent)).toEqual(['가맹점', '등록일', '사업자', '사업자등록번호'])
    expect(basics).not.toHaveTextContent('활성')
  })
})
