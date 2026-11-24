import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MerchantsScreen } from './MerchantsScreen'
import { BusinessesScreen } from './BusinessesScreen'
import type { MerchantListItem } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { HISTORY_TARGET_LABELS } from '../lib/historyTarget'
import { MERCHANT_CHANGE_TYPE_LABELS } from '../lib/merchantChangeTypes'
import { listCountText } from '../lib/listCount'
import { domOccurrences } from '../test/domScan'
import { apiError, callsContaining, fakeApi, leakedCalls, storageIsEmpty } from '../test/fakeApi'
import { draftMinimalDetail, individualRegisteredDetail, listItemOf, lookupItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-IA-001 B6(명세 §3.1·§4.1·§4.4·§7 B6 — 사용자 결정 D-ADMIN-UI-011 ① pg-lab 목록 패턴, B1 리뷰 NIT-2 용어):
 * 두 목록(가맹점·사업자)의 검색 조건을 한 카드 안 '항목명 | 입력' 칸(필터 묶음)으로 묶고 아래 오른쪽 '초기화'·'검색', 결과 표 아래 '총 n건'(keyset — 더 있으면 "n건 표시 · 더 있음").
 * '초기화'는 입력·결과를 비우는 화면 동작만(서버 호출 없음). 검색 조건·호출 인자·keyset·가맹점 코드 단일 기준·번호 입력 즉시 비움은 그대로.
 * 용어: 찾기 버튼 '검색', 다음 쪽 '더 보기'(이어 붙임), 작업 열 이름 '상세', '사이트' → 'Site'. 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const BRN = '1234567890'
const SHOWN = '123-45-67890'
const merchantRow = (n: number, name: string): MerchantListItem => ({
  merchantId: id('mer', n), merchantCode: `demoshop${String(n).padStart(2, '0')}`, merchantName: name, businessId: id('biz', 1), businessName: '데모 사업자',
  registrationNumber: BRN, lifecycle: 'ACTIVE', entitlementRevision: 1, midCount: 1, createdAt: '2026-10-01T00:00:00.000Z',
})
const panel = (name: string) => screen.getByRole('form', { name })
const labelsOf = (form: HTMLElement) => Array.from(form.querySelectorAll('.filterlabel')).map((l) => l.textContent)
const actionsOf = (form: HTMLElement) => Array.from(form.querySelectorAll('.filteractions button')).map((b) => b.textContent)

describe('MERCHANT-IA-001 B6 — 가맹점 목록 필터 묶음', () => {
  it('한 카드(.filterpanel) 안 이름 | 입력 칸 3개(보이는 label로 연결) + 아래 초기화·검색; 평소 안내는 번호 감사 한 줄(입력 규칙 3줄 없음); style 0', () => {
    const { container } = render(<MerchantsScreen api={fakeApi()} canSearchRegistrationNumber />)
    const form = panel('가맹점 검색')
    expect(form).toHaveClass('filterpanel')
    expect(labelsOf(form)).toEqual(['가맹점 코드', '가맹점명', '사업자등록번호'])
    for (const name of ['가맹점 코드', '가맹점명', '사업자등록번호']) {
      const input = within(form).getByLabelText(name)
      expect(input.closest('.filterfield')!.querySelector('label')).toHaveAttribute('for', input.id)
    }
    expect(actionsOf(form)).toEqual(['초기화', '검색'])
    expect(within(form).getByRole('button', { name: '초기화' })).toHaveAttribute('type', 'button')
    expect(within(form).getByRole('button', { name: '검색' })).toHaveAttribute('type', 'submit')
    expect(screen.getByLabelText('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호 검색은 매번 감사 기록에 남습니다(정확히 일치하는 번호만).')
    expect(screen.queryByText(/가맹점 코드: 정확히 일치하는 코드 하나/)).toBeNull()
    expect(screen.queryByText(/가맹점명: 앞부분이 같은 이름/)).toBeNull()
    expect(container.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('번호 칸이 없는 역할: 칸 2개, 감사 안내 줄 없음', () => {
    render(<MerchantsScreen api={fakeApi()} canSearchRegistrationNumber={false} />)
    expect(labelsOf(panel('가맹점 검색'))).toEqual(['가맹점 코드', '가맹점명'])
    expect(screen.queryByText(/검색은 매번 감사 기록/)).toBeNull()
  })

  it('표: 가맹점(이름·코드) | 사업자(상호 · 번호 한 칸) | MID 수 | 등록일 | 상세; 아래 건수 — 더 있으면 "n건 표시 · 더 있음" + 더 보기(이어 붙임, 같은 keyset 호출), 다 읽으면 "총 n건"', async () => {
    const merchantSearch = vi.fn()
      .mockResolvedValueOnce({ items: [merchantRow(1, '하나'), merchantRow(2, '둘')], nextCursor: id('mer', 2) })
      .mockResolvedValueOnce({ items: [merchantRow(3, '셋')], nextCursor: null })
    const { container } = render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber={false} />)
    await user.click(screen.getByRole('button', { name: '검색' }))
    const table = await screen.findByRole('table', { name: '가맹점 목록' })
    expect(within(table).getAllByRole('columnheader').map((h) => h.textContent)).toEqual(['가맹점', '사업자', 'MID 수', '등록일', '상세'])
    expect(within(screen.getByRole('row', { name: 'demoshop01' })).getAllByRole('cell')[1]).toHaveTextContent(`데모 사업자 · ${SHOWN}`)
    const bottom = container.querySelector('.tablebottom')!
    expect(bottom).toHaveTextContent('2건 표시 · 더 있음')
    await user.click(within(bottom as HTMLElement).getByRole('button', { name: '더 보기' }))
    expect(merchantSearch).toHaveBeenNthCalledWith(2, { after: id('mer', 2) })
    expect(await screen.findByRole('row', { name: 'demoshop03' })).toBeInTheDocument()
    expect(screen.getByRole('row', { name: 'demoshop01' })).toBeInTheDocument()
    expect(bottom).toHaveTextContent('총 3건')
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(screen.queryByRole('button', { name: '다음' })).toBeNull()
    expect(domOccurrences(document.body, 'mer_')).toEqual([])
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('초기화: 입력·오류·결과를 비우고 서버를 부르지 않는다(다시 검색하면 빈 조건 {})', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [merchantRow(1, '하나')], nextCursor: null }))
    render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber />)
    await user.type(screen.getByLabelText('가맹점명'), '데모')
    await user.click(screen.getByRole('button', { name: '검색' }))
    await screen.findByRole('row', { name: 'demoshop01' })
    await user.type(screen.getByLabelText('가맹점 코드'), 'ab')
    await user.type(screen.getByLabelText('사업자등록번호'), '123')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(screen.getByLabelText('사업자등록번호')).toHaveAttribute('aria-invalid', 'true')
    expect(merchantSearch).toHaveBeenCalledTimes(1)
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(merchantSearch).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('가맹점 코드')).toHaveValue('')
    expect(screen.getByLabelText('가맹점명')).toHaveValue('')
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(screen.getByLabelText('사업자등록번호')).not.toHaveAttribute('aria-invalid')
    expect(screen.queryByText('사업자등록번호는 숫자 10자리입니다.')).toBeNull()
    expect(screen.queryByRole('table', { name: '가맹점 목록' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(merchantSearch).toHaveBeenNthCalledWith(2, {})
  })

  // B6 리뷰 NIT-3: 초기화는 목록 요청 순번을 올린다 — 검색 중에도 누를 수 있고, 늦게 온 응답은 비운 화면을 다시 채우지 않는다.
  it('초기화는 서버 오류 안내도 지운다; 검색 중 초기화하면 진행 중 응답을 버린다(검색 버튼은 막힘)', async () => {
    let answer!: (v: { items: MerchantListItem[]; nextCursor: string | null }) => void
    const merchantSearch = vi.fn()
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockImplementationOnce(() => new Promise((r) => { answer = r }))
    render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber={false} />)
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(screen.queryByRole('alert')).toBeNull()
    await user.type(screen.getByLabelText('가맹점명'), '데모')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(screen.getByRole('button', { name: '검색' })).toBeDisabled()
    expect(screen.getByRole('button', { name: '초기화' })).toBeEnabled()
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(screen.getByRole('button', { name: '검색' })).toBeEnabled()
    answer({ items: [merchantRow(1, '하나')], nextCursor: null })
    await new Promise((r) => setTimeout(r, 20))
    expect(screen.queryByRole('table', { name: '가맹점 목록' })).toBeNull()
    expect(screen.getByLabelText('가맹점명')).toHaveValue('')
    expect(merchantSearch).toHaveBeenCalledTimes(2)
  })

  it('리뷰 M-1·NIT-6: 가맹점 코드 칸은 placeholder 예시만, 칸 아래 짧은 설명 "정확히 일치 · 예전 코드도 찾음"이 늘 보이고 칸이 가리킨다(은퇴 코드 검색 — MC-2)', () => {
    render(<MerchantsScreen api={fakeApi()} canSearchRegistrationNumber />)
    const code = screen.getByLabelText('가맹점 코드')
    expect(code).toHaveAttribute('placeholder', '예: demoshop01')
    const hint = screen.getByText('정확히 일치 · 예전 코드도 찾음')
    expect(hint).toHaveClass('filterhint')
    expect(hint.closest('.filterfield')).toContainElement(code)
    expect(code).toHaveAccessibleDescription('정확히 일치 · 예전 코드도 찾음')
  })

  it('리뷰 NIT-2: 가맹점명 형식 거절(400 INVALID_INPUT) 때 칸이 aria-invalid, 다시 성공하면 풀린다', async () => {
    const merchantSearch = vi.fn().mockRejectedValueOnce(apiError(400, 'INVALID_INPUT')).mockResolvedValueOnce({ items: [], nextCursor: null })
    render(<MerchantsScreen api={fakeApi({ merchantSearch })} canSearchRegistrationNumber={false} />)
    const name = screen.getByLabelText('가맹점명')
    expect(name).not.toHaveAttribute('aria-invalid')
    await user.type(name, ' 데모')
    await user.click(screen.getByRole('button', { name: '검색' }))
    await screen.findByRole('alert')
    expect(name).toHaveAttribute('aria-invalid', 'true')
    await user.clear(name)
    await user.type(name, '데모')
    await user.click(screen.getByRole('button', { name: '검색' }))
    await vi.waitFor(() => expect(name).not.toHaveAttribute('aria-invalid'))
  })

  it('번호 검색 결과: 번호는 보낸 즉시 비우고 결과 칸 하나에만 하이픈 표시, "총 1건"; 저장소·URL 0; 초기화도 번호를 다시 보내지 않는다', async () => {
    const merchantSearch = vi.fn(async () => ({ items: [merchantRow(1, '하나')], nextCursor: null }))
    const api = fakeApi({ merchantSearch })
    const { container } = render(<MerchantsScreen api={api} canSearchRegistrationNumber />)
    await user.type(screen.getByLabelText('사업자등록번호'), BRN)
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(merchantSearch).toHaveBeenCalledWith({ registrationNumber: BRN })
    await screen.findByRole('row', { name: 'demoshop01' })
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(container.querySelector('.tablebottom')).toHaveTextContent('총 1건')
    expect(domOccurrences(container, BRN)).toEqual([])
    expect(domOccurrences(container, SHOWN)).toEqual(['-/DIV/textContent', `-/TD/text:데모 사업자 · ${SHOWN}`])
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(merchantSearch).toHaveBeenCalledTimes(1)
    expect(storageIsEmpty()).toBe(true)
    expect(window.location.href).not.toContain(BRN)
  })
})

describe('MERCHANT-IA-001 B6 — 사업자 목록 필터 묶음', () => {
  const REG = individualRegisteredDetail(1)
  const DRAFT = draftMinimalDetail(2)
  const renderList = (overrides: Parameters<typeof fakeApi>[0] = {}, memory = createScreenMemory()) => {
    const api = fakeApi(overrides)
    const view = render(<BusinessesScreen api={api} memory={memory} canEdit onContinue={vi.fn()} handoffState={() => 'empty'} />)
    return { api, view, memory }
  }

  it('한 카드 안 이름 | 입력 칸 5개(등록일은 시작~끝 한 칸 — role=group) + 아래 초기화·검색("조회" 버튼 없음); 번호 감사 안내 한 줄이 번호 칸을 설명; style 0', async () => {
    const { view } = renderList({ searchBusinesses: vi.fn(async () => ({ items: [listItemOf(REG)], next: null })) })
    await screen.findByRole('table', { name: '사업자 목록' })
    const form = panel('사업자 검색')
    expect(form).toHaveClass('filterpanel')
    expect(labelsOf(form)).toEqual(['사업자등록번호', '상호', '사업체 구분', '등록 상태', '등록일'])
    expect(within(form).getByRole('group', { name: '등록일' })).toContainElement(screen.getByLabelText('등록일 시작'))
    expect(within(form).getByRole('group', { name: '등록일' })).toContainElement(screen.getByLabelText('등록일 끝'))
    for (const name of ['사업자등록번호', '상호', '사업체 구분', '등록 상태']) expect(within(form).getByLabelText(name)).toBeInTheDocument()
    expect(actionsOf(form)).toEqual(['초기화', '검색'])
    expect(screen.queryByRole('button', { name: '조회' })).toBeNull()
    expect(screen.getByLabelText('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호 검색은 매번 감사 기록에 남습니다. 번호로 찾으면 다른 조건은 쓰지 않습니다.')
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('표 아래 건수: 다 읽으면 "총 n건", 더 있으면 "n건 표시 · 더 있음" + 더 보기(같은 keyset 호출)', async () => {
    const searchBusinesses = vi.fn()
      .mockResolvedValueOnce({ items: [listItemOf(REG)], next: 'c1' })
      .mockResolvedValueOnce({ items: [listItemOf(DRAFT)], next: null })
    renderList({ searchBusinesses })
    expect(await screen.findByText('1건 표시 · 더 있음')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '더 보기' }))
    expect(searchBusinesses).toHaveBeenNthCalledWith(2, { after: 'c1' })
    expect(await screen.findByText('총 2건')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
  })

  it('초기화: 조건·번호·결과·오류를 비우고 서버를 부르지 않는다 — 같은 메모리로 다시 그려도 첫 쪽을 자동으로 읽지 않는다; 다음 검색은 빈 조건 {}', async () => {
    const searchBusinesses = vi.fn(async () => ({ items: [listItemOf(REG)], next: null }))
    const lookupBusinessByRegistrationNumber = vi.fn(async () => ({ items: [] }))
    const memory = createScreenMemory()
    const first = renderList({ searchBusinesses, lookupBusinessByRegistrationNumber }, memory)
    await screen.findByText('총 1건')
    await user.type(screen.getByLabelText('상호'), '데모')
    await user.selectOptions(screen.getByLabelText('등록 상태'), 'DRAFT')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(searchBusinesses).toHaveBeenNthCalledWith(2, { displayNamePrefix: '데모', registrationStatus: 'DRAFT' })
    await user.type(screen.getByLabelText('사업자등록번호'), '123')
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(screen.getByText('사업자등록번호는 숫자 10자리입니다.')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '초기화' }))
    expect(searchBusinesses).toHaveBeenCalledTimes(2)
    expect(lookupBusinessByRegistrationNumber).not.toHaveBeenCalled()
    expect(screen.getByLabelText('상호')).toHaveValue('')
    expect(screen.getByLabelText('등록 상태')).toHaveValue('')
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(screen.queryByText('사업자등록번호는 숫자 10자리입니다.')).toBeNull()
    expect(screen.queryByRole('table', { name: '사업자 목록' })).toBeNull()
    expect(screen.getByText("검색 조건을 넣고 '검색'을 누르세요.")).toBeInTheDocument()
    first.view.unmount()
    renderList({ searchBusinesses, lookupBusinessByRegistrationNumber }, memory)
    await new Promise((r) => setTimeout(r, 0))
    expect(searchBusinesses).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('table', { name: '사업자 목록' })).toBeNull()
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(searchBusinesses).toHaveBeenNthCalledWith(3, {})
    expect(await screen.findByText('총 1건')).toBeInTheDocument()
  })

  it('번호 검색 결과(짧은 4열)도 "총 n건"; 번호는 번호 조회 요청에만·입력란 즉시 비움(그대로); 검색 중에는 초기화가 막힌다', async () => {
    let answer!: (v: { items: ReturnType<typeof lookupItemOf>[] }) => void
    const lookupBusinessByRegistrationNumber = vi.fn(() => new Promise<{ items: ReturnType<typeof lookupItemOf>[] }>((r) => { answer = r }))
    const { api, view } = renderList({ searchBusinesses: vi.fn(async () => ({ items: [], next: null })), lookupBusinessByRegistrationNumber })
    await screen.findByText('총 0건')
    await user.type(screen.getByLabelText('사업자등록번호'), BRN)
    await user.click(screen.getByRole('button', { name: '검색' }))
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(screen.getByRole('button', { name: '초기화' })).toBeDisabled()
    answer({ items: [lookupItemOf(REG)] })
    expect(await screen.findByText('총 1건')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '초기화' })).toBeEnabled()
    expect(callsContaining(api, BRN)).toEqual(['lookupBusinessByRegistrationNumber'])
    expect(leakedCalls(api, BRN)).toEqual([])
    expect(domOccurrences(view.container, BRN)).toEqual([])
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })
})

describe('MERCHANT-IA-001 B6 — 용어 통일(B1 리뷰 NIT-2)', () => {
  it("변경 이력 대상·변경 이름에 '사이트' 없음 — 'Site'로 통일", () => {
    const labels = [...Object.values(HISTORY_TARGET_LABELS), ...Object.values(MERCHANT_CHANGE_TYPE_LABELS)]
    expect(labels.filter((l) => l.includes('사이트'))).toEqual([])
    expect(HISTORY_TARGET_LABELS.MerchantSite).toBe('Site')
    expect(MERCHANT_CHANGE_TYPE_LABELS.MerchantSiteRegistered).toBe('Site 등록')
  })

  it('건수 문구: keyset이라 다 읽었을 때만 "총"', () => {
    expect(listCountText(3, false)).toBe('총 3건')
    expect(listCountText(20, true)).toBe('20건 표시 · 더 있음')
  })
})
