import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen } from './BusinessesScreen'
import type { BusinessDetail } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { callsContaining, fakeApi, leakedCalls } from '../test/fakeApi'
import { draftMinimalDetail, individualRegisteredDetail, listItemOf, lookupItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-IA-001 B2(명세 docs/repository/MERCHANT-IA-001-SCREEN-ITEMS.md §7 B2 — 사용자 결정 D-ADMIN-UI-010 ②·⑦):
 * ② 늘 같은 값은 화면에서 뺀다(DB·API·타입 유지) — 목록 '진위확인' 열, 상세 머리 "진위확인 미검증" 배지, 기본정보 '진위확인'·'국가'·'등록 상태'(머리 배지와 중복) 줄.
 *   "등록 완료는 … 진위확인·AML/KYB·계약 승인이 아닙니다." 안내는 반드시 남는다(이 시험이 고정).
 * ⑦ 사업자등록번호로 찾은 결과는 짧은 결과 모양 — 사업자등록번호·상호·등록 상태·상세 4열(값 없는 '—' 칸을 그리지 않는다). 일반 검색 표는 진위확인 열만 빠진다.
 * 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
/** 시험 입력 전용 사업자등록번호(Demo — 실제 번호 아님). */
const BRN = '7781234560'
const REG = individualRegisteredDetail(1)
const DRAFT = draftMinimalDetail(2)
const pageOf = (details: BusinessDetail[]) => ({ items: details.map(listItemOf), next: null })

function renderScreen(overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi(overrides)
  render(<BusinessesScreen api={api} memory={createScreenMemory()} canEdit onContinue={vi.fn()} handoffState={() => 'empty'} />)
  return api
}
const table = () => screen.getByRole('table', { name: '사업자 목록' })
const headers = () => within(table()).getAllByRole('columnheader').map((h) => h.textContent)
const rows = () => within(table()).getAllByRole('row').slice(1)

describe('MERCHANT-IA-001 B2 — 사업자 목록', () => {
  it('② 일반 검색 표: 진위확인 열 없음(나머지 열·값 그대로)', async () => {
    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG, DRAFT])) })
    await screen.findByRole('table', { name: '사업자 목록' })
    expect(headers()).toEqual(['사업자등록번호', '상호', '대표자', '사업체 구분', '등록 상태', '가맹점 수', '수정일', '보기'])
    await vi.waitFor(() => expect(rows()).toHaveLength(2))
    expect(rows()[0]).toHaveTextContent('등록 완료')
    expect(rows()[0]).not.toHaveTextContent('미검증')
    expect(within(rows()[0]!).getAllByRole('cell')).toHaveLength(8)
  })

  it('⑦ 번호 조회 결과: 사업자등록번호·상호·등록 상태·상세 4열만, "—" 칸 없음; 0건은 같은 4열 빈 행; 번호는 번호 조회 요청에만', async () => {
    const lookup = vi.fn().mockResolvedValueOnce({ items: [lookupItemOf(DRAFT)] }).mockResolvedValueOnce({ items: [] })
    const api = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), lookupBusinessByRegistrationNumber: lookup })
    await screen.findByRole('table', { name: '사업자 목록' })
    await user.click(screen.getByLabelText('사업자등록번호'))
    await user.paste(BRN)
    await user.click(screen.getByRole('button', { name: '조회' }))
    await vi.waitFor(() => expect(rows()).toHaveLength(1))
    expect(headers()).toEqual(['사업자등록번호', '상호', '등록 상태', '상세'])
    const cells = within(rows()[0]!).getAllByRole('cell')
    expect(cells.map((c) => c.textContent)).toEqual(['123-45-67890', '임시 사업자', '임시저장', '상세'])
    expect(table()).not.toHaveTextContent('—')
    expect(within(rows()[0]!).getByRole('button', { name: '임시 사업자 상세' })).toBeInTheDocument()
    await user.click(screen.getByLabelText('사업자등록번호'))
    await user.paste(BRN)
    await user.click(screen.getByRole('button', { name: '조회' }))
    expect(await screen.findByText('이 번호로 등록된 사업자가 없습니다.')).toBeInTheDocument()
    expect(headers()).toEqual(['사업자등록번호', '상호', '등록 상태', '상세'])
    expect(screen.getByText('이 번호로 등록된 사업자가 없습니다.').closest('td')).toHaveAttribute('colspan', '4')
    // 감사·속도 제한 안내는 그대로(번호 조회 안내 문구).
    expect(screen.getByLabelText('사업자등록번호')).toHaveAccessibleDescription('번호로 찾으면 다른 조건은 쓰지 않습니다(조회는 감사 기록에 남습니다).')
    expect(callsContaining(api, BRN)).toEqual(['lookupBusinessByRegistrationNumber'])
    expect(leakedCalls(api, BRN)).toEqual([])
  })

  it('⑦ 번호 조회 뒤 일반 조회로 돌아오면 일반 표 열(8열)로 돌아온다', async () => {
    const lookup = vi.fn(async () => ({ items: [lookupItemOf(DRAFT)] }))
    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), lookupBusinessByRegistrationNumber: lookup })
    await screen.findByRole('table', { name: '사업자 목록' })
    await user.click(screen.getByLabelText('사업자등록번호'))
    await user.paste(BRN)
    await user.click(screen.getByRole('button', { name: '조회' }))
    await vi.waitFor(() => expect(headers()).toHaveLength(4))
    await user.click(screen.getByRole('button', { name: '조회' }))
    await vi.waitFor(() => expect(headers()).toHaveLength(8))
  })
})

describe('MERCHANT-IA-001 B2 — 사업자 상세', () => {
  it('② 머리에 진위확인 배지 없음, 기본정보에 진위확인·국가·등록 상태 줄 없음; 등록 상태 배지와 "등록 완료 ≠ 진위확인·AML/KYB·계약 승인" 안내는 남는다', async () => {
    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: vi.fn(async () => REG) })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    const section = await screen.findByRole('region', { name: '사업자 상세' })
    await within(section).findByText('사업자등록번호')
    expect(section).not.toHaveTextContent('진위확인 미검증')
    expect(section).not.toHaveTextContent('미검증')
    const terms = Array.from(section.querySelectorAll('dt')).map((dt) => dt.textContent)
    for (const gone of ['진위확인', '국가', '등록 상태']) expect(terms).not.toContain(gone)
    for (const kept of ['사업자등록번호', '상호', '사업체 구분', '개업일', '영문 상호', '가맹점 수', '등록일', '수정일']) expect(terms).toContain(kept)
    // 등록 상태는 머리 배지 하나로(줄과 중복이던 값).
    expect(within(section).getAllByText('등록 완료')).toHaveLength(1)
    expect(section).toHaveTextContent('등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.')
  })

  it('② 임시저장 사업자도 같은 안내와 DRAFT 경고가 남는다(진위확인 표시 없음)', async () => {
    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([DRAFT])), businessDetail: vi.fn(async () => DRAFT) })
    await user.click(await screen.findByRole('button', { name: '임시 사업자 상세' }))
    const section = await screen.findByRole('region', { name: '사업자 상세' })
    await within(section).findByText('사업자등록번호')
    expect(section).toHaveTextContent('등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.')
    expect(section).toHaveTextContent('임시저장 사업자에는 새 가맹점을 등록할 수 없습니다')
    expect(section).not.toHaveTextContent('미검증')
    // 리뷰 MINOR-2: 기본정보 '등록 상태' 줄을 뺀 근거 — 등록 상태는 머리 배지 하나로 남는다.
    const head = within(section).getByRole('heading', { level: 2, name: '임시 사업자' }).parentElement!
    expect(within(head).getByText('임시저장')).toBeInTheDocument()
    expect(Array.from(section.querySelectorAll('dt')).map((dt) => dt.textContent)).not.toContain('등록 상태')
  })
})
