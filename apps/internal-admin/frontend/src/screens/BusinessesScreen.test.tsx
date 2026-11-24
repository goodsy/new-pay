import { describe, expect, it, vi } from 'vitest'
import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen, openBusinessIn } from './BusinessesScreen'
import type { BusinessDetail } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, callsContaining, fakeApi, leakedCalls, storageIsEmpty } from '../test/fakeApi'
import {
  bizId, commandRejected, commandSucceeded, draftMinimalDetail, HISTORY, individualRegisteredDetail, jointRegisteredDetail, legacyDraftDetail, listItemOf, lookupItemOf,
} from '../test/businessFixtures'

/**
 * BUSINESS-PROFILE-001 §14 BIZ-4-3·BIZ-4-5 — 사업자 조회 탭(§8.2): 목록(keyset "더 보기")·번호 조회·상세(개인정보 원문 — D-BIZ-006 ②)·이력(마스킹 전후·사유).
 * 결정 고정: X-4·D-ADMIN-UI-004 ③(biz_ 화면·URL·저장소 0건), C-6(번호는 번호 조회 API만 — 목록 본문에 번호 없음), BIZ-3 리뷰 1(빈 조건은 키 생략 — null 없음),
 * 진위확인(TC-21 — 값은 늘 NOT_CHECKED)은 화면에 그리지 않는다(MERCHANT-IA-001 B2, D-ADMIN-UI-010 ②), BIZ-3 리뷰 4(429 문구 — Demo 값), L-7(DRAFT 안내). 시험 값은 명시 Demo fixture.
 */

const user = userEvent.setup({ delay: null })
const BRN = '7781234560'
const paste = async (label: string, value: string) => {
  await user.click(screen.getByLabelText(label))
  await user.paste(value)
}
const press = async (name: string) => user.click(screen.getByRole('button', { name }))
// 기대값 변경(MERCHANT-IA-001 B6 — 사용자 결정 D-ADMIN-UI-011 ① 목록 필터 묶음·용어 통일, 명세 §4.1·§7.7): 목록 검색 버튼 '조회' → '검색', 칸 이름 '상호(앞부분 일치)' → '상호'(규칙은 placeholder '앞부분 일치').
const SEARCH = '검색'

type Overrides = Parameters<typeof fakeApi>[0]
/** 제출마다 다른 submissionId(32 hex). */
function nextSubmissionId() {
  let n = 0
  return () => (++n).toString(16).padStart(32, '0')
}
function renderScreen(overrides: Overrides = {}, canEdit = true, memory = createScreenMemory()) {
  const api = fakeApi(overrides)
  const onContinue = vi.fn()
  const view = render(<BusinessesScreen api={api} memory={memory} canEdit={canEdit} onContinue={onContinue} handoffState={() => 'empty'} newSubmissionId={nextSubmissionId()} />)
  return { api, onContinue, view, memory }
}

const REG = individualRegisteredDetail(1)
const DRAFT = draftMinimalDetail(2)
const JOINT = { ...jointRegisteredDetail(4), business: { ...jointRegisteredDetail(4).business, businessName: '공동 사업자' } }
const LEGACY = legacyDraftDetail(3)
const pageOf = (details: BusinessDetail[], next: string | null = null) => ({ items: details.map(listItemOf), next })
const rows = () => within(screen.getByRole('table', { name: '사업자 목록' })).getAllByRole('row').slice(1)

describe('BusinessesScreen — 목록·검색 (BIZ-4-3, §8.2)', () => {
  it('lists with the raw number (hyphenated — D-BIZ-007), name, masked representative + "외 N명", 구분 (미입력), status, merchant count — no Business ID or 진위확인 column; "더 보기" pages by cursor', async () => {
    const search = vi.fn()
      .mockResolvedValueOnce(pageOf([REG, JOINT, LEGACY], bizId(3)))
      .mockResolvedValueOnce(pageOf([DRAFT]))
    const { view } = renderScreen({ searchBusinesses: search })
    expect(screen.getByRole('heading', { name: '사업자 조회' })).toBeInTheDocument()
    await screen.findByRole('table', { name: '사업자 목록' })
    expect(search).toHaveBeenCalledWith({})
    const headers = within(screen.getByRole('table', { name: '사업자 목록' })).getAllByRole('columnheader').map((h) => h.textContent)
    // 기대값 변경(MERCHANT-IA-001 B2 — 사용자 결정 D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다": 진위확인은 늘 '미검증'(DB CHECK NOT_CHECKED만), 값·API는 그대로).
    // 기대값 변경(MERCHANT-IA-001 B6 — 사용자 결정 D-ADMIN-UI-011 ① 목록 필터 묶음·용어 통일, 명세 §4.1·§7.7): 마지막 열 이름 '보기' → '상세'.
    expect(headers).toEqual(['사업자등록번호', '상호', '대표자', '사업체 구분', '등록 상태', '가맹점 수', '수정일', '상세'])
    await vi.waitFor(() => expect(rows()).toHaveLength(3))
    expect(rows()[0]).toHaveTextContent('778-12-34567')
    expect(rows()[0]).toHaveTextContent('데모 사업자')
    expect(rows()[0]).toHaveTextContent('홍**')
    expect(rows()[0]).toHaveTextContent('개인')
    expect(rows()[0]).toHaveTextContent('등록 완료')
    expect(rows()[0]).not.toHaveTextContent('미검증')
    expect(rows()[1]).toHaveTextContent('홍** 외 1명')
    expect(rows()[2]).toHaveTextContent('미입력')
    expect(rows()[2]).toHaveTextContent('임시저장')
    await press('더 보기')
    await vi.waitFor(() => expect(rows()).toHaveLength(4))
    expect(search).toHaveBeenNthCalledWith(2, { after: bizId(3) })
    expect(screen.queryByRole('button', { name: '더 보기' })).toBeNull()
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    expect(window.location.href).not.toContain('biz_')
    expect(storageIsEmpty()).toBe(true)
  })

  it('filters send only filled keys (no null, no empty string — BIZ-3 리뷰 1); the list body never carries a registration number (C-6)', async () => {
    const search = vi.fn(async () => pageOf([REG]))
    renderScreen({ searchBusinesses: search })
    await screen.findByRole('table', { name: '사업자 목록' })
    // 빈 조건으로 "조회" → 본문 `{}`(키 생략 — null·빈 문자열 없음).
    await press(SEARCH)
    expect(search).toHaveBeenLastCalledWith({})
    await paste('상호', '데모')
    await user.selectOptions(screen.getByLabelText('사업체 구분'), 'INDIVIDUAL')
    await user.selectOptions(screen.getByLabelText('등록 상태'), 'REGISTERED')
    await user.type(screen.getByLabelText('등록일 시작'), '2026-11-01')
    await press(SEARCH)
    expect(search).toHaveBeenLastCalledWith({ displayNamePrefix: '데모', businessType: 'INDIVIDUAL', registrationStatus: 'REGISTERED', createdFrom: '2026-11-01' })
    for (const call of search.mock.calls) expect(JSON.stringify(call)).not.toMatch(/registrationNumber|null/)
  })

  it('a number search uses the number lookup only (other conditions disabled), clears the number and shows the 0/1 result in the same table', async () => {
    const lookup = vi.fn().mockResolvedValueOnce({ items: [lookupItemOf(DRAFT)] }).mockResolvedValueOnce({ items: [] })
    const search = vi.fn(async () => pageOf([REG]))
    const { api, view } = renderScreen({ searchBusinesses: search, lookupBusinessByRegistrationNumber: lookup })
    await screen.findByRole('table', { name: '사업자 목록' })
    await paste('사업자등록번호', BRN)
    expect(screen.getByLabelText('상호')).toBeDisabled()
    await press(SEARCH)
    expect(lookup).toHaveBeenCalledWith(BRN)
    expect(search).toHaveBeenCalledTimes(1)
    await vi.waitFor(() => expect(rows()).toHaveLength(1))
    expect(rows()[0]).toHaveTextContent('임시 사업자')
    expect(rows()[0]).toHaveTextContent('123-45-67890')
    expect(rows()[0]).toHaveTextContent('임시저장')
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    expect(view.container.innerHTML).not.toContain(BRN)
    await paste('사업자등록번호', BRN)
    await press(SEARCH)
    expect(await screen.findByText('이 번호로 등록된 사업자가 없습니다.')).toBeInTheDocument()
    // 리뷰 MINOR-3: 원문 번호는 번호 조회 요청에만(목록 검색 등 모든 대역 호출 인수 전수).
    expect(callsContaining(api, BRN)).toEqual(['lookupBusinessByRegistrationNumber'])
    expect(leakedCalls(api, BRN)).toEqual([])
  })

  it('429 wording: the shared read window (30/분) for lists and the BRN budget (10/분) for lookups — both marked Demo values', async () => {
    const search = vi.fn(async () => { throw apiError(429, 'RATE_LIMITED') })
    const lookup = vi.fn(async () => { throw apiError(429, 'RATE_LIMITED') })
    renderScreen({ searchBusinesses: search, lookupBusinessByRegistrationNumber: lookup })
    expect(await screen.findByText(/사업자 조회 30회\/분/)).toHaveTextContent('Demo 값')
    await paste('사업자등록번호', BRN)
    await press(SEARCH)
    expect(await screen.findByText(/사업자등록번호 10회\/분/)).toHaveTextContent('Demo 값')
  })
})

describe('BusinessesScreen — 상세·이력 (BIZ-4-3·BIZ-4-5, §8.2·§9)', () => {
  it('detail shows PII in plain text (D-BIZ-006 ②), the number raw (hyphenated — D-BIZ-007), no "미검증", the REGISTERED meaning line; OPERATOR + REGISTERED → "이 사업자로 가맹점 등록" hands over name + number only', async () => {
    const detail = vi.fn(async () => REG)
    const { api, onContinue, view } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: detail })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    const section = await screen.findByRole('region', { name: '사업자 상세' })
    expect(api.businessDetail).toHaveBeenCalledWith(bizId(1))
    expect(api.businessDetail).toHaveBeenCalledTimes(1)
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): OPERATOR 상세는 편집 폼이라 값이 입력칸에 있다 — 글자 또는 입력값으로 본다(원문 표시 규칙 D-BIZ-006 ② 그대로).
    const shown = (text: string) => (section.textContent ?? '').includes(text) || Array.from(section.querySelectorAll('input, textarea')).some((el) => (el as HTMLInputElement).value === text)
    for (const text of ['778-12-34567', '홍길동', '김담당', 'contact.demo@example.test', '010-1234-5678', '02-1234-5678', '서울특별시 중구 세종대로 110', '데모동 101호', '도소매', '전자상거래', '등록 완료']) {
      expect(shown(text), text).toBe(true)
    }
    // 기대값 변경(MERCHANT-IA-001 B2 — 사용자 결정 D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다": 진위확인은 늘 '미검증'(DB CHECK NOT_CHECKED만), 값·API는 그대로): 머리 배지·기본정보 줄 모두 없다. 아래 "등록 완료 ≠ 진위확인" 안내는 그대로.
    expect(section).not.toHaveTextContent('미검증')
    expect(section).toHaveTextContent('등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.')
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    await press('이 사업자로 가맹점 등록')
    expect(onContinue).toHaveBeenCalledWith({ businessId: bizId(1), name: '데모 사업자', registrationNumber: '7781234567' })
    expect(window.location.href).not.toContain('biz_')
    // BRN-UNMASK-001(D-BIZ-007·D-PII-001 ①, PII §6 #10): 화면에는 원문(하이픈)이지만 번호는 식별자가 아니다 — URL·브라우저 저장소에 없고, 마스킹 문자열도 남지 않는다.
    expect(section).not.toHaveTextContent('*******')
    expect(window.location.href).not.toContain('7781234567')
    expect(window.location.href).not.toContain('778-12-34567')
    expect(storageIsEmpty()).toBe(true)
  })

  it('DRAFT detail: no merchant-registration handoff, L-7 guidance, the edit form with 임시저장·등록 완료; APPROVER (view only) sees no command buttons and no form', async () => {
    const first = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([DRAFT])), businessDetail: vi.fn(async () => DRAFT) })
    await user.click(await screen.findByRole('button', { name: '임시 사업자 상세' }))
    const section = await screen.findByRole('region', { name: '사업자 상세' })
    expect(section).toHaveTextContent('임시저장 사업자에는 새 가맹점을 등록할 수 없습니다')
    expect(screen.queryByRole('button', { name: '이 사업자로 가맹점 등록' })).toBeNull()
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): '이어서 입력·등록 완료' 버튼 대신 같은 화면의 편집 폼(임시저장·등록 완료 — 기존 흐름 그대로).
    expect(await screen.findByRole('region', { name: '사업자 정보 편집' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '등록 완료' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '임시저장' })).toBeInTheDocument()
    first.view.unmount()

    renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: vi.fn(async () => REG) }, false)
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    await screen.findByRole('region', { name: '사업자 상세' })
    for (const name of ['저장', '임시저장', '등록 완료', '이 사업자로 가맹점 등록']) expect(screen.queryByRole('button', { name })).toBeNull()
    expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull()
  })

  it('history shows time, kind, reason and masked before/after; "더 보기" pages with ?after=<seq>; DOM has no biz_', async () => {
    const history = vi.fn().mockResolvedValueOnce({ items: [HISTORY[0]!], nextAfter: 41 }).mockResolvedValueOnce({ items: [HISTORY[1]!], nextAfter: null })
    const { api, view } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: vi.fn(async () => REG), businessHistory: history })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    await user.click(await screen.findByRole('tab', { name: '변경 이력' }))
    const table = await screen.findByRole('table', { name: '변경 이력' })
    expect(api.businessHistory).toHaveBeenCalledWith(bizId(1), undefined)
    await vi.waitFor(() => expect(table).toHaveTextContent('생성'))
    expect(table).toHaveTextContent('대표자: 추가 홍**(단독)')
    await press('더 보기')
    expect(api.businessHistory).toHaveBeenLastCalledWith(bizId(1), 41)
    await vi.waitFor(() => expect(table).toHaveTextContent('상호 정정'))
    expect(table).toHaveTextContent('상호: 데모 사업자 (구) → 데모 사업자')
    expect(table).toHaveTextContent('등록 담당자: 종료 김**')
    expect(table).not.toHaveTextContent('contact.demo@example.test')
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('openBusinessIn (상세 링크 — D-BIZ-003·D-BIZ-006 ③) opens the detail from memory only (no URL); a missing business shows the fixed message', async () => {
    const memory = createScreenMemory()
    openBusinessIn(memory, bizId(1))
    const detail = vi.fn(async () => { throw apiError(404, 'BUSINESS_NOT_FOUND') })
    renderScreen({ businessDetail: detail, searchBusinesses: vi.fn(async () => pageOf([])) }, true, memory)
    expect(await screen.findByText('사업자를 찾을 수 없습니다.')).toBeInTheDocument()
    expect(detail).toHaveBeenCalledWith(bizId(1))
    expect(window.location.href).not.toContain('biz_')
  })

  it('edit form → save → the detail is read again (C-18) and the form shows the new values', async () => {
    const updated = { ...REG, business: { ...REG.business, version: 4 }, registration: { ...REG.registration, email: 'biz.demo@example.test' } }
    const detail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValueOnce(updated)
    const { api } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: detail, updateBusiness: vi.fn(async () => commandSucceeded(bizId(1))) })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): '수정' 버튼 없이 상세가 곧 편집 폼(제목 줄 대신 상세 머리).
    expect(await screen.findByRole('region', { name: '사업자 정보 편집' })).toBeInTheDocument()
    await paste('사업체 이메일(선택)', 'biz.demo@example.test')
    await press('저장')
    // 다시 읽은 상세(version 4)로 폼이 새로 그려진다 — 값은 입력칸에.
    await vi.waitFor(() => expect(api.businessDetail).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('biz.demo@example.test'))
    expect(vi.mocked(api.updateBusiness).mock.calls[0]![1]).toMatchObject({ version: 3 })
  })

  /**
   * 리뷰 MINOR-1(낙관적 잠금 우회 방지): A의 수정이 결과 불명(v3 기준)으로 잠긴 사이 B가 v4로 바꾸고, A가 상세 링크로 v4를 다시 읽는다.
   * 수정 화면은 잠긴 편집(기준 v3)으로 이어서 열리고 재전송 본문 version은 3(서버가 BUSINESS_VERSION_CONFLICT로 판정) — 지금 상세 version 4가 아니다.
   * "그래도 새로 입력"은 편집 메모리를 비우고 상세를 다시 읽으며, 다음 수정은 v4 내용·version 4로 시작한다(옛 v3 내용 + version 4 조합 없음).
   */
  it('review MINOR-1: a locked v3 edit survives a v4 re-read — the resend carries base version 3 (server conflict); abandoning re-reads and the next edit starts from v4', async () => {
    const v4 = { ...REG, business: { ...REG.business, version: 4 }, registration: { ...REG.registration, email: 'b.change@example.test' } }
    const detail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValueOnce(v4).mockResolvedValueOnce(v4).mockResolvedValueOnce(v4)
    const update = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(commandRejected('BUSINESS_VERSION_CONFLICT'))
      .mockResolvedValueOnce(commandRejected('BUSINESS_VERSION_CONFLICT'))
      .mockResolvedValueOnce(commandSucceeded(bizId(1)))
    const { memory } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: detail, updateBusiness: update })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): '수정' 단계 없이 상세의 편집 폼에서 바로 입력(아래 잠금·재전송·version 단언은 그대로).
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'a.change@example.test')
    await press('저장')
    await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(update.mock.calls[0]![1]).toMatchObject({ version: 3, registration: { email: 'a.change@example.test' } })

    // 상세 링크(다른 화면 — 중복 안내 등)로 같은 사업자를 다시 연다 → v4 재조회. 잠긴 편집은 버려지지 않는다(안내).
    act(() => openBusinessIn(memory, bizId(1)))
    const section = await screen.findByRole('region', { name: '사업자 상세' })
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): 다시 읽은 v4 문서는 폼이 아니라 머리에 보이고(상호 등), 폼은 잠긴 편집(기준 v3)으로 그대로 열린다 — 다시 읽기가 일어났는지는 호출 수로 본다.
    await vi.waitFor(() => expect(detail).toHaveBeenCalledTimes(2))
    await vi.waitFor(() => expect(section).toHaveTextContent('결과 확인이 필요한 저장이 있습니다'))
    expect(memory.keys().some((k) => k === 'edit.cmd.status')).toBe(true)

    // 잠긴 편집(기준 v3)이 이어서 열려 있다. 재전송 본문 = 처음과 같음(version 3) → 서버 충돌.
    await press('같은 내용 다시 보내기')
    expect(await screen.findByRole('alert')).toHaveTextContent('다른 사용자가 먼저 수정했습니다')
    expect(update.mock.calls[1]![1]).toEqual(update.mock.calls[0]![1])
    expect(update.mock.calls[1]![1]).toMatchObject({ version: 3 })
    // 확정된 충돌 뒤 같은 폼으로 새로 저장해도 기준 version 3(지금 상세 v4가 아님) — 새 submissionId, 서버가 다시 충돌로 판정(덮어쓰기 없음).
    await press('저장')
    await vi.waitFor(() => expect(update).toHaveBeenCalledTimes(3))
    expect(update.mock.calls[2]![1]).toMatchObject({ version: 3, registration: { email: 'a.change@example.test' } })
    expect(update.mock.calls[2]![1].submissionId).not.toBe(update.mock.calls[0]![1].submissionId)

    // 다시 불러오기 → 편집 비움·상세 재조회 → 다음 수정은 v4 기준(B의 변경 보존, version 4).
    await press('다시 불러오기')
    await screen.findByRole('region', { name: '사업자 상세' })
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('b.change@example.test'))
    await paste('영문 상호(선택)', 'Demo Shop')
    await press('저장')
    await vi.waitFor(() => expect(update).toHaveBeenCalledTimes(4))
    expect(update.mock.calls[3]![1]).toMatchObject({ version: 4, registration: { email: 'b.change@example.test', englishName: 'Demo Shop' } })
  })

  it('review MINOR-1: "그래도 새로 입력" on a locked edit clears the edit memory and re-reads the detail (no v3 content with version 4)', async () => {
    const v4 = { ...REG, business: { ...REG.business, version: 4 }, registration: { ...REG.registration, email: 'b.change@example.test' } }
    const detail = vi.fn().mockResolvedValueOnce(REG).mockResolvedValueOnce(v4).mockResolvedValue(v4)
    const update = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN')).mockResolvedValueOnce(commandSucceeded(bizId(1)))
    const { memory } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: detail, updateBusiness: update })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'a.change@example.test')
    await press('저장')
    await screen.findByRole('group', { name: '결과 확인 필요' })
    await press('취소하고 새로 입력')
    await press('그래도 새로 입력')
    await screen.findByRole('region', { name: '사업자 상세' })
    await vi.waitFor(() => expect(screen.getByLabelText('사업체 이메일(선택)')).toHaveValue('b.change@example.test'))
    expect(detail).toHaveBeenCalledTimes(2)
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): 편집 기준은 상세를 열 때 자동으로 고정되므로 남는 편집 키는 새 기준(edit.base = v4) 하나뿐 — 옛 v3 입력·잠금·보낸 본문은 모두 지워졌다(단언의 뜻 그대로).
    expect(memory.keys().filter((k) => k.startsWith('edit.'))).toEqual(['edit.base'])
    expect((memory.read('edit.base') as { business: { version: number } }).business.version).toBe(4)
    await paste('영문 상호(선택)', 'Demo Shop')
    await press('저장')
    await vi.waitFor(() => expect(update).toHaveBeenCalledTimes(2))
    expect(update.mock.calls[1]![1]).toMatchObject({ version: 4, registration: { email: 'b.change@example.test' } })
  })

  it('review MINOR-1: a locked edit of business A is not dropped when another business B is opened — B shows no edit form, with guidance', async () => {
    const other = individualRegisteredDetail(5, '다른 사업자')
    const detail = vi.fn(async (id: string) => (id === bizId(5) ? other : REG))
    const update = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
    const { memory } = renderScreen({ searchBusinesses: vi.fn(async () => pageOf([REG])), businessDetail: detail, updateBusiness: update })
    await user.click(await screen.findByRole('button', { name: '데모 사업자 상세' }))
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'a.change@example.test')
    await press('저장')
    await screen.findByRole('group', { name: '결과 확인 필요' })
    act(() => openBusinessIn(memory, bizId(5)))
    await vi.waitFor(() => expect(screen.getByRole('region', { name: '사업자 상세' })).toHaveTextContent('다른 사업자'))
    // 기대값 변경(MERCHANT-IA-001 B5 — 사용자 결정 D-ADMIN-UI-011 ② "사업자 상세 = 항상 편집 폼 + 하단 저장", D-ADMIN-UI-010 ⑥ 대체): B는 '수정'을 누를 필요 없이 처음부터 편집 폼을 열지 않고(읽기 표시) 안내한다 — A의 잠긴 편집은 그대로.
    expect(screen.getByText(/다른 사업자에 결과 확인이 필요한 저장이 있어/)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull()
    expect((memory.read('edit.cmd.status') as { kind: string }).kind).toBe('unknown')
    await press('결과 확인이 필요한 사업자 열기')
    expect(await screen.findByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
  })

  it('review NIT-6: a failed first list load is not refetched automatically on tab re-entry — only "다시 시도" reads again', async () => {
    const search = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_UNAVAILABLE')).mockResolvedValueOnce(pageOf([REG]))
    const memory = createScreenMemory()
    const first = renderScreen({ searchBusinesses: search }, true, memory)
    expect(await screen.findByText(/연결할 수 없습니다/)).toBeInTheDocument()
    first.view.unmount()
    renderScreen({ searchBusinesses: search }, true, memory)
    expect(screen.getByText(/연결할 수 없습니다/)).toBeInTheDocument()
    expect(search).toHaveBeenCalledTimes(1)
    await press('다시 시도')
    await vi.waitFor(() => expect(rows()).toHaveLength(1))
    expect(search).toHaveBeenCalledTimes(2)
  })
})

