import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen, openBusinessIn } from './BusinessesScreen'
import type { BusinessDetail, MerchantListItem } from '../api/adminApi'
import { createScreenMemory, hasUnresolvedRequest } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, fakeApi } from '../test/fakeApi'
import { bizId, commandRejected, commandSucceeded, draftMinimalDetail, individualRegisteredDetail, listItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-IA-001 B5(명세 §4.2·§7 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장"(D-ADMIN-UI-010 ⑥ 대체), D-ADMIN-UI-010 ⑧ '연결 가맹점' 탭):
 * - OPERATOR: 기본정보 탭이 곧 편집 폼(섹션 회사(2열) / 대표자 / 담당자, 하단 '저장' + '목록으로'), 바뀐 칸이 없으면 저장 비활성. 조회 역할: 같은 배치의 읽기 표시.
 * - 저장 규칙 그대로: 중요정보 사유, version 충돌 → 다시 불러오기, 결과 불명 잠금(같은 submissionId 재전송), 저장 뒤 다시 읽기.
 * - 저장하지 않은 변경이 있으면 목록으로·탭 이동·가맹점 상세 열기 전에 화면 안에서 묻는다.
 * - 연결 가맹점: 기존 가맹점 검색(사업자 조건 businessId, keyset '다음') — 가맹점(이름·코드)·MID 수·등록일·상세, '상세' → 가맹점 상세(호출자).
 * 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
const REG = individualRegisteredDetail(1)
const DRAFT = draftMinimalDetail(2)
const pageOf = (details: BusinessDetail[]) => ({ items: details.map(listItemOf), next: null })
const merchantId = (n: number) => `mer_${String(n).padStart(26, '0')}`
const merchant = (n: number, name: string): MerchantListItem => ({
  merchantId: merchantId(n), merchantCode: `shop${String(n).padStart(6, '0')}`, merchantName: name, businessId: bizId(1), businessName: '데모 사업자', registrationNumber: '7781234567',
  lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: n, createdAt: '2026-11-18T00:00:00.000Z',
})

function renderScreen(overrides: Parameters<typeof fakeApi>[0] = {}, canEdit = true, memory = createScreenMemory()) {
  const api = fakeApi({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: vi.fn(async () => REG), ...overrides })
  const onOpenMerchant = vi.fn()
  let n = 0
  const view = render(<BusinessesScreen api={api} memory={memory} canEdit={canEdit} onContinue={vi.fn()} handoffState={() => 'empty'} onOpenMerchant={onOpenMerchant}
    newSubmissionId={() => (++n).toString(16).padStart(32, '0')} />)
  return { api, onOpenMerchant, view, memory }
}
const openDetail = async (name = '데모 사업자') => {
  await user.click(await screen.findByRole('button', { name: `${name} 상세` }))
  return screen.findByRole('region', { name: '사업자 상세' })
}
const paste = async (label: string, value: string) => {
  const el = screen.getByLabelText(label)
  await user.clear(el)
  await user.click(el)
  await user.paste(value)
}

describe('MERCHANT-IA-001 B5 — 사업자 상세 편집 폼', () => {
  it('OPERATOR: 섹션 회사(2열)·대표자·담당자 편집 폼, 번호는 읽기 표시(원문 하이픈), 국가 칸 없음; 하단 저장(바뀐 칸이 없으면 비활성) + 목록으로; style·내부 키 0', async () => {
    const { view } = renderScreen()
    await openDetail()
    const form = await screen.findByRole('region', { name: '사업자 정보 편집' })
    expect(Array.from(form.querySelectorAll('section[aria-label]')).map((s) => s.getAttribute('aria-label'))).toEqual(['회사', '대표자', '담당자'])
    const company = within(form).getByRole('region', { name: '회사' })
    expect(company.querySelector('.lg\\:grid-cols-2')).not.toBeNull()
    expect(within(company).getByRole('group', { name: '사업자등록번호' })).toHaveTextContent('778-12-34567')
    expect(within(form).queryByLabelText('국가')).toBeNull()
    expect(within(company).getByLabelText('상호')).toHaveValue('데모 사업자')
    const save = within(form).getByRole('button', { name: '저장' })
    expect(save).toBeDisabled()
    expect(within(form).getByText('바뀐 칸이 없습니다.')).toBeInTheDocument()
    expect(within(within(form).getByRole('group', { name: '저장' })).getByRole('button', { name: '목록으로' })).toBeInTheDocument()
    expect(screen.getAllByRole('button', { name: '목록으로' })).toHaveLength(1)
    await paste('사업체 이메일(선택)', 'biz.demo@example.test')
    expect(save).toBeEnabled()
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('조회 역할: 같은 배치(회사·대표자·담당자)의 읽기 표시 — 입력칸·저장 없음, 위쪽 목록으로', async () => {
    renderScreen({}, false)
    const section = await openDetail()
    await within(section).findByText('사업자등록번호')
    expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull()
    expect(Array.from(section.querySelectorAll('[role="tabpanel"]:not([hidden]) section[aria-label], [role="tabpanel"]:not([hidden]) h4')).map((e) => e.getAttribute('aria-label') ?? e.textContent)).toEqual(['회사', '대표자', '담당자'])
    const info = document.getElementById(screen.getByRole('tab', { name: '기본정보' }).getAttribute('aria-controls')!)!
    expect(info.querySelectorAll('input, select, textarea')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull()
    expect(screen.getByRole('button', { name: '목록으로' })).toBeInTheDocument()
  })

  it('중요정보(상호)를 바꾸면 변경 사유가 필요하다 — 없으면 보내지 않고, 넣으면 사유·기준 version과 함께 보낸 뒤 다시 읽는다', async () => {
    const updated = { ...REG, business: { ...REG.business, businessName: '새 상호', version: 4 } }
    const updateBusiness = vi.fn(async () => commandSucceeded(bizId(1)))
    const businessDetail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValue(updated)
    renderScreen({ updateBusiness, businessDetail })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('상호', '새 상호')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(updateBusiness).not.toHaveBeenCalled()
    expect(screen.getByLabelText('변경 사유')).toHaveAttribute('aria-invalid', 'true')
    await paste('변경 사유', '상호 변경 신고')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(updateBusiness).toHaveBeenCalledWith(bizId(1), expect.objectContaining({ version: 3, reason: '상호 변경 신고', displayName: '새 상호' }))
    await vi.waitFor(() => expect(businessDetail).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(screen.getByRole('heading', { level: 2, name: '새 상호' })).toBeInTheDocument())
  })

  it('version 충돌: 거절 안내 + "다시 불러오기" → 상세를 다시 읽고 새 version 기준 폼(내 입력은 버림)', async () => {
    const v4 = { ...REG, business: { ...REG.business, version: 4 }, registration: { ...REG.registration, email: 'other@example.test' } }
    const updateBusiness = vi.fn().mockResolvedValueOnce(commandRejected('BUSINESS_VERSION_CONFLICT')).mockResolvedValueOnce(commandSucceeded(bizId(1)))
    const businessDetail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValue(v4)
    renderScreen({ updateBusiness, businessDetail })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'mine@example.test')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('다른 사용자가 먼저 수정했습니다')
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('other@example.test'))
    await paste('영문 상호(선택)', 'Demo')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(updateBusiness.mock.calls[1]![1]).toMatchObject({ version: 4 })
  })

  it('결과 불명: 폼이 잠기고, 목록으로 나갔다 와도(이탈 확인 없음 — 잃는 것이 없다) 같은 submissionId로만 다시 보낸다', async () => {
    const updateBusiness = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN')).mockResolvedValueOnce(commandSucceeded(bizId(1)))
    const { memory } = renderScreen({ updateBusiness })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'locked@example.test')
    await user.click(screen.getByRole('button', { name: '저장' }))
    await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(screen.getByLabelText('사업체 이메일(선택)')).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(screen.queryByRole('alertdialog', { name: '저장하지 않은 변경' })).toBeNull()
    expect(hasUnresolvedRequest(memory)).toBe(true)
    await openDetail()
    expect(await screen.findByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '같은 내용 다시 보내기' }))
    expect(updateBusiness.mock.calls.map((c) => c[1])).toEqual([updateBusiness.mock.calls[0]![1], updateBusiness.mock.calls[0]![1]])
    expect(updateBusiness.mock.calls[1]![1]).toMatchObject({ version: 3, registration: { email: 'locked@example.test' } })
  })

  it('이탈 확인: 바꾼 채 목록으로 → 화면 안 확인(계속 편집은 그대로, 버리고 이동하면 다시 열 때 원래 값); 탭 이동은 확인 뒤 옮기고 입력은 남는다', async () => {
    renderScreen()
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'draft@example.test')
    await user.click(screen.getByRole('tab', { name: '변경 이력' }))
    const tabDialog = screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })
    expect(tabDialog).toHaveTextContent('입력은 기본정보 탭에 남습니다')
    expect(screen.getByRole('tab', { name: '기본정보' })).toHaveAttribute('aria-selected', 'true')
    await user.click(within(tabDialog).getByRole('button', { name: '탭 옮기기' }))
    expect(screen.getByRole('tab', { name: '변경 이력' })).toHaveAttribute('aria-selected', 'true')
    await user.click(screen.getByRole('tab', { name: '기본정보' }))
    expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('draft@example.test')

    await user.click(screen.getByRole('button', { name: '목록으로' }))
    const dialog = screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })
    expect(dialog).toHaveTextContent('이동하면 입력한 내용이 사라집니다')
    await user.click(within(dialog).getByRole('button', { name: '계속 편집' }))
    expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('draft@example.test')
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    await user.click(within(screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })).getByRole('button', { name: '변경 버리고 이동' }))
    expect(await screen.findByRole('table', { name: '사업자 목록' })).toBeInTheDocument()
    await openDetail()
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue(''))
  })

  it('DRAFT: 같은 폼에서 임시저장(바뀐 칸이 없으면 비활성)·등록 완료(그대로 누를 수 있다 — 기존 흐름), 안내 유지', async () => {
    const completeBusinessRegistration = vi.fn(async () => commandRejected('BUSINESS_REGISTRATION_INCOMPLETE'))
    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([DRAFT])), businessDetail: vi.fn(async () => DRAFT), completeBusinessRegistration })
    const section = await openDetail('임시 사업자')
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    expect(section).toHaveTextContent('등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.')
    expect(screen.getByRole('button', { name: '임시저장' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '등록 완료' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: '등록 완료' }))
    // 화면 검사(필수값)가 먼저 막는다 — 보내지 않고 칸 오류(기존 흐름 그대로).
    expect(completeBusinessRegistration).not.toHaveBeenCalled()
    expect(screen.getByText('입력을 확인하세요 — 표시한 칸을 고친 뒤 다시 저장하세요.')).toBeInTheDocument()
  })
})

describe('MERCHANT-IA-001 B5 — 연결 가맹점 탭', () => {
  it('탭 이름에 가맹점 수, 처음 열 때 사업자 조건으로 가맹점 검색(다음 쪽 이어 읽기); 행 = 이름·코드·MID 수·등록일·상세; 내부 키 0', async () => {
    const merchantSearch = vi.fn()
      .mockResolvedValueOnce({ items: [merchant(1, '첫째 가맹점')], nextCursor: merchantId(1) })
      .mockResolvedValueOnce({ items: [merchant(2, '둘째 가맹점')], nextCursor: null })
    const { onOpenMerchant, view } = renderScreen({ merchantSearch })
    await openDetail()
    const tab = await screen.findByRole('tab', { name: '연결 가맹점 (1)' })
    expect(merchantSearch).not.toHaveBeenCalled()
    await user.click(tab)
    expect(merchantSearch).toHaveBeenCalledWith({ businessId: bizId(1) })
    const table = await screen.findByRole('table', { name: '연결 가맹점 목록' })
    // 기대값 변경(MERCHANT-IA-001 B6 — 명세 §7.7 용어 통일): 작업 열 이름 '상세'(보이는 머리 — 목록들과 같은 이름).
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['가맹점', 'MID 수', '등록일', '상세'])
    const row = within(table).getByRole('row', { name: 'shop000001' })
    expect(row).toHaveTextContent('첫째 가맹점')
    expect(within(row).getAllByRole('cell')[1]).toHaveTextContent('1')
    await user.click(screen.getByRole('button', { name: '더 보기' }))
    expect(merchantSearch).toHaveBeenNthCalledWith(2, { businessId: bizId(1), after: merchantId(1) })
    expect(await within(table).findByRole('row', { name: 'shop000002' })).toBeInTheDocument()
    await user.click(within(within(table).getByRole('row', { name: 'shop000002' })).getByRole('button', { name: '둘째 가맹점 가맹점 상세' }))
    expect(onOpenMerchant).toHaveBeenCalledWith(merchantId(2))
    expect(domOccurrences(view.container, 'mer_')).toEqual([])
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('저장하지 않은 변경이 있으면 가맹점 상세로 가기 전에 묻는다', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [merchant(1, '첫째 가맹점')], nextCursor: null }))
    const { onOpenMerchant } = renderScreen({ merchantSearch })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'draft@example.test')
    await user.click(screen.getByRole('tab', { name: '연결 가맹점 (1)' }))
    await user.click(within(screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })).getByRole('button', { name: '탭 옮기기' }))
    await user.click(await screen.findByRole('button', { name: '첫째 가맹점 가맹점 상세' }))
    expect(onOpenMerchant).not.toHaveBeenCalled()
    await user.click(within(screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })).getByRole('button', { name: '변경 버리고 이동' }))
    expect(onOpenMerchant).toHaveBeenCalledWith(merchantId(1))
  })
})

describe('MERCHANT-IA-001 B5 리뷰 반영', () => {
  it('MINOR-1: 같은 사업자를 목록에서 다시 열면 연결 가맹점을 다시 읽는다', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [merchant(1, '첫째 가맹점')], nextCursor: null }))
    renderScreen({ merchantSearch }, false)
    await openDetail()
    await user.click(await screen.findByRole('tab', { name: '연결 가맹점 (1)' }))
    await screen.findByRole('table', { name: '연결 가맹점 목록' })
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    await openDetail()
    await user.click(await screen.findByRole('tab', { name: '연결 가맹점 (1)' }))
    await screen.findByRole('table', { name: '연결 가맹점 목록' })
    expect(merchantSearch).toHaveBeenCalledTimes(2)
  })

  it('MINOR-2: 키보드로 탭을 옮기려 하면 확인이 열리며 초점은 "계속 편집"(설명 연결); Tab은 두 버튼 사이만, Esc는 계속 편집(초점은 원래 탭), "탭 옮기기"는 새 탭으로 초점', async () => {
    renderScreen()
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'draft@example.test')
    const info = screen.getByRole('tab', { name: '기본정보' })
    info.focus()
    await user.keyboard('{ArrowRight}')
    const dialog = screen.getByRole('alertdialog', { name: '저장하지 않은 변경' })
    expect(dialog).toHaveAccessibleDescription('저장하지 않은 변경이 있습니다. 탭을 옮겨도 입력은 기본정보 탭에 남습니다.')
    expect(within(dialog).getByRole('button', { name: '계속 편집' })).toHaveFocus()
    await user.keyboard('{Tab}')
    expect(within(dialog).getByRole('button', { name: '탭 옮기기' })).toHaveFocus()
    await user.keyboard('{Tab}')
    expect(within(dialog).getByRole('button', { name: '계속 편집' })).toHaveFocus()
    await user.keyboard('{Shift>}{Tab}{/Shift}')
    expect(within(dialog).getByRole('button', { name: '탭 옮기기' })).toHaveFocus()
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('alertdialog', { name: '저장하지 않은 변경' })).toBeNull()
    expect(info).toHaveFocus()
    expect(info).toHaveAttribute('aria-selected', 'true')
    await user.keyboard('{ArrowRight}')
    await user.keyboard('{Tab}{Enter}')
    const linked = screen.getByRole('tab', { name: '연결 가맹점 (1)' })
    expect(linked).toHaveAttribute('aria-selected', 'true')
    expect(linked).toHaveFocus()
  })

  it('MINOR-3: 다른 화면 링크로 같은 사업자를 새 version으로 다시 읽어 저장하지 않은 입력이 버려지면 한 줄로 알린다(이탈 확인은 없음)', async () => {
    const v4 = { ...REG, business: { ...REG.business, version: 4 }, registration: { ...REG.registration, email: 'other@example.test' } }
    const businessDetail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValue(v4)
    const { memory } = renderScreen({ businessDetail })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'mine@example.test')
    act(() => openBusinessIn(memory, bizId(1)))
    const notice = await screen.findByRole('status', { name: '버려진 입력' })
    expect(notice).toHaveTextContent('저장하지 않은 입력은 새로 읽은 사업자 정보로 다시 불러와 버려졌습니다')
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('other@example.test'))
    expect(screen.queryByRole('alertdialog', { name: '저장하지 않은 변경' })).toBeNull()
    await user.click(within(notice).getByRole('button', { name: '닫기' }))
    expect(screen.queryByRole('status', { name: '버려진 입력' })).toBeNull()
  })

  it('NIT-1: 연결 가맹점 응답이 오기 전에 다른 사업자를 열면 그 응답은 버린다', async () => {
    let resolve: (v: { items: MerchantListItem[]; nextCursor: string | null }) => void = () => undefined
    const merchantSearch = vi.fn(() => new Promise<{ items: MerchantListItem[]; nextCursor: string | null }>((r) => { resolve = r }))
    const other = individualRegisteredDetail(5, '다른 사업자')
    const { memory } = renderScreen({ merchantSearch, businessDetail: vi.fn(async (id: string) => (id === bizId(5) ? other : REG)) }, false)
    await openDetail()
    await user.click(await screen.findByRole('tab', { name: '연결 가맹점 (1)' }))
    act(() => openBusinessIn(memory, bizId(5)))
    await vi.waitFor(() => expect(screen.getByRole('heading', { level: 2, name: '다른 사업자' })).toBeInTheDocument())
    await act(async () => resolve({ items: [merchant(1, '첫째 가맹점')], nextCursor: null }))
    expect(memory.read('linked') ?? null).toBeNull()
    expect(screen.queryByText('첫째 가맹점')).toBeNull()
  })
})
