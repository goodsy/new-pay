import { describe, expect, it, vi } from 'vitest'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'
import { createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, callsContaining, fakeApi, leakedCalls, storageIsEmpty } from '../test/fakeApi'
import { bizId, commandSucceeded, draftMinimalDetail, individualRegisteredDetail, lookupItemOf } from '../test/businessFixtures'

/**
 * MERCHANT-IA-001 B5b(명세 §4.2a·§7 B5b — 사용자 결정 D-ADMIN-UI-011 ①, D-BIZ-003, D-MOPS-003·006 ④, D-BIZLOOKUP-001, D-PII-001):
 * 사업자 등록 = 사업자등록번호 먼저 확인. 1단계 한 칸(하이픈 자동) + '확인'(형식이 맞을 때만 번호 조회 — 매 확인 감사·번호 공유 버킷은 서버).
 * 이미 있으면 "이미 등록한 사업자입니다" + 상호·번호(원문 하이픈)·등록 상태 + '정보 보기'(내부 키 화면 없음). 없으면 2단계 입력 폼 — 번호는 읽기 전용으로 고정, '번호 다시 입력'으로 1단계.
 * 확인 → 등록 사이 다시 조회하지 않는다(최종 중복 판정은 서버 등록 시). 원문 번호는 조회·등록 요청 본문에만, URL·저장소·DOM 원문 0. 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
const BRN = '7781234560'
const SHOWN = '778-12-34560'

function renderCreate(overrides: Parameters<typeof fakeApi>[0] = {}, memory = createScreenMemory()) {
  const api = fakeApi({ lookupBusinessByRegistrationNumber: vi.fn(async () => ({ items: [] })), ...overrides })
  const onOpenBusiness = vi.fn()
  const onSaved = vi.fn()
  const onBack = vi.fn()
  const onSessionEnded = vi.fn()
  let n = 0
  const view = render(<BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={memory} workspace onSaved={onSaved} onOpenBusiness={onOpenBusiness} onBack={onBack}
    onSessionEnded={onSessionEnded} newSubmissionId={() => (++n).toString(16).padStart(32, '0')} />)
  return { api, onOpenBusiness, onSaved, onBack, onSessionEnded, view, memory }
}
const typeNumber = async (value: string) => {
  const input = screen.getByLabelText('사업자등록번호')
  await user.clear(input)
  await user.click(input)
  await user.paste(value)
}
const check = () => user.click(screen.getByRole('button', { name: '등록 여부 확인' }))

describe('MERCHANT-IA-001 B5b — 사업자 등록 1단계(번호 확인)', () => {
  it('처음 화면은 번호 한 칸 + 확인 + 목록으로뿐(섹션·저장 없음); 하이픈 자동; 형식이 틀리면 조회를 부르지 않는다; style 0', async () => {
    const { api, onBack, view } = renderCreate()
    expect(screen.getByRole('form', { name: '등록 여부 확인' })).toBeInTheDocument()
    expect(screen.queryAllByRole('region')).toEqual([])
    expect(screen.queryByRole('group', { name: '저장' })).toBeNull()
    await typeNumber(BRN)
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue(SHOWN)
    await typeNumber('77812')
    await check()
    expect(api.lookupBusinessByRegistrationNumber).not.toHaveBeenCalled()
    expect(screen.getByLabelText('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호는 숫자 10자리입니다.')
    await user.click(screen.getByRole('button', { name: '목록으로' }))
    expect(onBack).toHaveBeenCalledTimes(1)
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('이미 있음 → "이미 등록한 사업자입니다" + 상호·번호(원문 하이픈)·등록 상태 + 정보 보기(사업자 상세로); 입력 폼으로 가지 않는다; 내부 키 0', async () => {
    const existing = draftMinimalDetail(4, '먼저 있던 사업자')
    const { api, onOpenBusiness, view } = renderCreate({ lookupBusinessByRegistrationNumber: vi.fn(async () => ({ items: [lookupItemOf(existing)] })) })
    await typeNumber('1234567890')
    await check()
    const result = await screen.findByRole('status', { name: '등록 여부 확인 결과' })
    expect(result).toHaveTextContent('이미 등록한 사업자입니다.')
    expect(result).toHaveTextContent('먼저 있던 사업자 · 123-45-67890 (임시저장)')
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    await user.click(within(result).getByRole('button', { name: '정보 보기' }))
    expect(onOpenBusiness).toHaveBeenCalledWith(bizId(4))
    expect(api.lookupBusinessByRegistrationNumber).toHaveBeenCalledTimes(1)
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('오류: 429는 번호 공유 버킷 문구(Demo 값), 403은 기존 권한 문구, 세션 종료는 로그인으로 — 모두 입력 폼으로 가지 않는다', async () => {
    const lookup = vi.fn()
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
      .mockRejectedValueOnce(apiError(403, 'FORBIDDEN'))
      .mockRejectedValueOnce(apiError(401, 'SESSION_EXPIRED'))
    const { onSessionEnded } = renderCreate({ lookupBusinessByRegistrationNumber: lookup })
    await typeNumber(BRN)
    await check()
    expect(await screen.findByText(/사업자등록번호 10회\/분/)).toHaveTextContent('Demo 값')
    // 리뷰 MINOR-2: 보낸 즉시 입력란을 비우므로(다른 번호 조회 화면과 같은 규칙 — D-BIZLOOKUP-001) 다시 확인하려면 번호를 다시 입력한다. 빈 칸 확인은 조회 없음.
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    await check()
    expect(lookup).toHaveBeenCalledTimes(1)
    await typeNumber(BRN)
    await check()
    expect(await screen.findByText('이 작업을 할 권한이 없습니다.')).toBeInTheDocument()
    await typeNumber(BRN)
    await check()
    await vi.waitFor(() => expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED'))
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
  })
})

describe('MERCHANT-IA-001 B5b 리뷰 MINOR-1 — 확인 중 번호 변경', () => {
  it.each(['이미 있음', '없음'] as const)('확인 중에는 입력란이 잠기고, 응답 전에 번호가 바뀌면 늦게 온 옛 결과(%s)는 적용하지 않는다', async (kind) => {
    let answer!: (value: { items: ReturnType<typeof lookupItemOf>[] }) => void
    const lookup = vi.fn(() => new Promise<{ items: ReturnType<typeof lookupItemOf>[] }>((resolve) => { answer = resolve }))
    renderCreate({ lookupBusinessByRegistrationNumber: lookup })
    await typeNumber(BRN)
    await check()
    const input = screen.getByLabelText('사업자등록번호')
    expect(input).toBeDisabled()
    expect(input).toHaveValue('')
    // 잠금을 우회한 변경(방어 2단) — 진행 중 확인의 결과는 버려진다.
    fireEvent.change(input, { target: { value: '1234567890' } })
    expect(input).toHaveValue('123-45-67890')
    expect(input).toBeEnabled()
    answer({ items: kind === '이미 있음' ? [lookupItemOf(draftMinimalDetail(4, '옛 번호 사업자'))] : [] })
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByRole('status', { name: '등록 여부 확인 결과' })).toBeNull()
    expect(screen.queryByRole('region', { name: '사업자 기본' })).toBeNull()
    expect(screen.getByRole('form', { name: '등록 여부 확인' })).toBeInTheDocument()
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('123-45-67890')
    expect(lookup).toHaveBeenCalledTimes(1)
  })
})

describe('MERCHANT-IA-001 B5b — 2단계(입력 폼)', () => {
  it('없음 → 입력 폼, 번호는 읽기 전용(원문 하이픈)·입력란 비움; 번호 다시 입력 → 1단계(다른 입력은 남는다)', async () => {
    const { api } = renderCreate()
    await typeNumber(BRN)
    await check()
    await screen.findByRole('region', { name: '사업자 기본' })
    const number = screen.getByRole('group', { name: '사업자등록번호' })
    expect(number).toHaveTextContent(SHOWN)
    expect(screen.queryByRole('form', { name: '등록 여부 확인' })).toBeNull()
    await user.click(screen.getByLabelText('상호'))
    await user.paste('데모 사업자')
    await user.click(within(number).getByRole('button', { name: '번호 다시 입력' }))
    expect(screen.getByLabelText('사업자등록번호')).toHaveValue('')
    await typeNumber('1234567890')
    await check()
    expect(await screen.findByRole('group', { name: '사업자등록번호' })).toHaveTextContent('123-45-67890')
    expect(screen.getByLabelText('상호')).toHaveValue('데모 사업자')
    expect(api.lookupBusinessByRegistrationNumber).toHaveBeenCalledTimes(2)
  })

  it('확인 1회 → 등록: 조회는 1회뿐(등록 전 다시 조회 없음), 원문 번호는 조회·등록 요청 본문에만; DOM·URL·저장소에 원문 10자리 없음; 내부 키 0', async () => {
    const createBusiness = vi.fn(async () => commandSucceeded(bizId(1)))
    const { api, onSaved, view } = renderCreate({ createBusiness })
    await typeNumber(BRN)
    await check()
    await screen.findByRole('region', { name: '사업자 기본' })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await user.click(screen.getByLabelText('상호'))
    await user.paste('데모 사업자')
    expect(domOccurrences(view.container, BRN)).toEqual([])
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    expect(createBusiness).toHaveBeenCalledWith(expect.objectContaining({ registrationStatus: 'DRAFT', registrationNumber: BRN, displayName: '데모 사업자' }))
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(bizId(1)))
    expect(api.lookupBusinessByRegistrationNumber).toHaveBeenCalledTimes(1)
    expect(api.lookupBusinessByRegistrationNumber).toHaveBeenCalledWith(BRN)
    expect(callsContaining(api, BRN).sort()).toEqual(['createBusiness', 'lookupBusinessByRegistrationNumber'])
    expect(leakedCalls(api, BRN)).toEqual([])
    expect(window.location.href).not.toContain(BRN)
    expect(window.location.href).not.toContain(SHOWN)
    expect(storageIsEmpty()).toBe(true)
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('결과 불명: 폼·번호 다시 입력이 잠기고, 화면을 다시 그려도(확인한 번호는 사라져도) 잠긴 등록의 번호로 2단계가 열려 같은 submissionId로만 다시 보낸다', async () => {
    const createBusiness = vi.fn().mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN')).mockResolvedValueOnce(commandSucceeded(bizId(1), true))
    const memory = createScreenMemory()
    const first = renderCreate({ createBusiness }, memory)
    await typeNumber(BRN)
    await check()
    await screen.findByRole('region', { name: '사업자 기본' })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await user.click(screen.getByLabelText('상호'))
    await user.paste('데모 사업자')
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(screen.getByRole('button', { name: '번호 다시 입력' })).toBeDisabled()
    first.view.unmount()

    const again = renderCreate({ createBusiness }, memory)
    expect(await screen.findByRole('group', { name: '결과 확인 필요' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: '사업자등록번호' })).toHaveTextContent(SHOWN)
    expect(screen.getByRole('button', { name: '번호 다시 입력' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '같은 내용 다시 보내기' }))
    await vi.waitFor(() => expect(again.onSaved).toHaveBeenCalledWith(bizId(1)))
    expect(createBusiness.mock.calls[1]![0]).toEqual(createBusiness.mock.calls[0]![0])
    expect(again.api.lookupBusinessByRegistrationNumber).not.toHaveBeenCalled()
  })

  it('리뷰 NIT-2(resetIfUnlocked 규칙): 등록 거절 뒤 번호 다시 입력 → 새 번호 확인(없음)이면 잠기지 않은 지난 거절 안내를 이어받지 않는다(다른 입력은 남는다)', async () => {
    const existing = individualRegisteredDetail(9, '먼저 등록된 사업자')
    renderCreate({ createBusiness: vi.fn(async () => ({ outcome: 'REJECTED' as const, rejection: 'BUSINESS_REGISTRATION_NUMBER_DUPLICATE', replayed: false, existing: lookupItemOf(existing) })) })
    await typeNumber(BRN)
    await check()
    await screen.findByRole('region', { name: '사업자 기본' })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await user.click(screen.getByLabelText('상호'))
    await user.paste('데모 사업자')
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    await user.click(screen.getByRole('button', { name: '번호 다시 입력' }))
    await typeNumber('1234567890')
    await check()
    expect(await screen.findByRole('group', { name: '사업자등록번호' })).toHaveTextContent('123-45-67890')
    expect(screen.queryByText(/이미 등록된 사업자등록번호입니다/)).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(screen.getByLabelText('상호')).toHaveValue('데모 사업자')
  })

  it('확인 뒤 다른 운영자가 먼저 등록한 경합: 등록 응답의 중복 안내(기존 사업자 상세 보기)가 그대로 나온다 — 최종 판정은 서버', async () => {
    const existing = individualRegisteredDetail(9, '먼저 등록된 사업자')
    const { onOpenBusiness } = renderCreate({ createBusiness: vi.fn(async () => ({ outcome: 'REJECTED' as const, rejection: 'BUSINESS_REGISTRATION_NUMBER_DUPLICATE', replayed: false, existing: lookupItemOf(existing) })) })
    await typeNumber(BRN)
    await check()
    await screen.findByRole('region', { name: '사업자 기본' })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await user.click(screen.getByLabelText('상호'))
    await user.paste('데모 사업자')
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    await user.click(within(alert).getByRole('button', { name: '기존 사업자 상세 보기' }))
    expect(onOpenBusiness).toHaveBeenCalledWith(bizId(9))
  })
})
