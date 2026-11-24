import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'
import { ApiError } from '../api/client'
import type { BusinessDetail } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { apiError, callsContaining, fakeApi, leakedCalls, storageIsEmpty } from '../test/fakeApi'
import {
  bizId, commandRejected, commandSucceeded, draftMinimalDetail, individualRegisteredDetail, jointRegisteredDetail, legacyDraftDetail, lookupItemOf,
} from '../test/businessFixtures'

/**
 * BUSINESS-PROFILE-001 §14 BIZ-4-2 — 사업자 등록·수정 폼(§8.3, 기존 BusinessRegistrationScreen 교체 — C-27).
 * 결정 고정: D-BIZ-005(등록 모드 "저장 후 변경할 수 없습니다" 안내·수정 모드 번호 읽기 전용), D-BIZ-003(중복 → 기존 상호·마스킹 번호·상세 보기),
 * §4.5 중요정보 변경 감지(r-6), BIZ-3 리뷰 2(모든 key·선택 null·countryCode KR·version·번호/상태 없음)·3(503 문구 구분), D-ADMIN-UI-004 ③(biz_ 화면 미표시).
 * 시험 값은 명시 Demo fixture(운영값 아님).
 */

// 부하 대응: 등록 완료 시험은 필수 칸을 모두 채운다 — 전체 게이트 부하에서 기본 5초를 넘지 않게 시간 상한만 20초(단언 불변).
const user = userEvent.setup({ delay: null })
const BRN = '7781234560'
const BIZ = bizId(1)
const region = (name: string) => screen.getByRole('region', { name })
const field = (label: string) => screen.getByLabelText(label)
const paste = async (label: string, value: string) => {
  const input = field(label)
  if ((input as HTMLInputElement).type === 'date') return user.type(input, value)
  await user.click(input)
  await user.paste(value)
}
const replace = async (label: string, value: string) => {
  await user.clear(field(label))
  if (value !== '') await paste(label, value)
}
const press = async (name: string) => user.click(screen.getByRole('button', { name }))
function ids() {
  let n = 0
  return () => (++n).toString(16).padStart(32, '0')
}
const sid = (n: number) => n.toString(16).padStart(32, '0')

type Overrides = Parameters<typeof fakeApi>[0]
function renderCreate(overrides: Overrides = {}) {
  const api = fakeApi(overrides)
  const onSaved = vi.fn()
  const onOpenBusiness = vi.fn()
  const memory = createScreenMemory()
  const view = render(<BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={memory} newSubmissionId={ids()} onSaved={onSaved} onOpenBusiness={onOpenBusiness} />)
  return { api, onSaved, onOpenBusiness, view, memory }
}
function renderEdit(detail: BusinessDetail, overrides: Overrides = {}) {
  const api = fakeApi(overrides)
  const onSaved = vi.fn()
  const onReload = vi.fn()
  const view = render(
    <BusinessProfileFormScreen api={api} mode={{ kind: 'edit', detail }} memory={createScreenMemory()} newSubmissionId={ids()} onSaved={onSaved} onOpenBusiness={vi.fn()} onReload={onReload} onCancel={vi.fn()} />,
  )
  return { api, onSaved, onReload, view }
}

/** REGISTERED 필수값 전부(개인). */
async function fillRegisteredIndividual() {
  await user.click(screen.getByRole('radio', { name: '개인' }))
  await paste('사업자등록번호', BRN)
  await paste('상호', '데모 사업자')
  await paste('개업일', '2026-01-01')
  await paste('업태 1', '도소매')
  await paste('종목 1', '전자상거래')
  await paste('사업장 우편번호', '04524')
  await paste('사업장 기본주소', '서울특별시 중구 세종대로 110')
  await paste('대표자 1 이름', '홍길동')
  await paste('사업체 전화', '0212345678')
  await paste('담당자 1 이름', '김담당')
  await paste('담당자 1 이메일', 'contact.demo@example.test')
  await paste('담당자 1 전화', '01012345678')
}

const REGISTERED_BODY = {
  registrationStatus: 'REGISTERED',
  registrationNumber: BRN,
  businessType: 'INDIVIDUAL',
  displayName: '데모 사업자',
  registration: { corporateRegistrationNumber: null, openingDate: '2026-01-01', countryCode: 'KR', englishName: null, phoneNumber: '0212345678', email: null },
  representatives: [{ name: '홍길동', representativeType: 'SOLE' }],
  categories: [{ categoryType: 'BUSINESS_TYPE', name: '도소매' }, { categoryType: 'BUSINESS_ITEM', name: '전자상거래' }],
  addresses: [{ addressType: 'REGISTERED', postalCode: '04524', baseAddress: '서울특별시 중구 세종대로 110', detailAddress: null }],
  contacts: [{ name: '김담당', email: 'contact.demo@example.test', phoneNumber: '01012345678', primary: true }],
}

describe('BusinessProfileFormScreen — 등록(생성) 모드 (BIZ-4-2, §8.3)', { timeout: 20_000 }, () => {
  it('renders sections 1~6 and the buttons (임시저장·등록 완료·취소); no 증빙 section; 국가 is KR read-only; D-BIZ-005 notice under the number and near the buttons', () => {
    const { view } = renderCreate()
    expect(screen.getByRole('heading', { name: '사업자 등록' })).toBeInTheDocument()
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(['사업자 기본', '사업 내용', '주소', '대표자', '사업체 연락', '등록 담당자'])
    expect(screen.queryByText(/증빙/)).toBeNull()
    expect(within(screen.getByRole('group', { name: '저장' })).getAllByRole('button').map((b) => b.textContent)).toEqual(['임시저장', '등록 완료', '취소'])
    expect(field('국가')).toHaveValue('KR')
    expect(field('국가')).toHaveAttribute('readonly')
    // D-BIZ-005: 번호 칸 아래 고정 문구 + 저장 버튼 근처 같은 뜻 한 줄(확인 대화상자 없음).
    expect(field('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호는 저장 후 변경할 수 없습니다. 저장 전에 번호를 확인하세요.')
    expect(within(screen.getByRole('group', { name: '저장' }).parentElement!).getByText('사업자등록번호는 저장 후 변경할 수 없습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('법인등록번호 is shown only for 법인 (hidden again for 개인); number/phone/corporate number show hyphens while the body carries digits only', async () => {
    const { api } = renderCreate({ createBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    expect(screen.queryByLabelText('법인등록번호')).toBeNull()
    await user.click(screen.getByRole('radio', { name: '법인' }))
    await paste('법인등록번호', '1101111234567')
    expect(field('법인등록번호')).toHaveValue('110111-1234567')
    await paste('사업자등록번호', BRN)
    expect(field('사업자등록번호')).toHaveValue('778-12-34560')
    await paste('사업체 전화', '0212345678')
    expect(field('사업체 전화')).toHaveValue('02-1234-5678')
    await paste('상호', '데모 법인')
    await press('임시저장')
    expect(api.createBusiness).toHaveBeenCalledTimes(1)
    const body = vi.mocked(api.createBusiness).mock.calls[0]![0]
    expect(body.registration.corporateRegistrationNumber).toBe('1101111234567')
    expect(body.registration.phoneNumber).toBe('0212345678')
    expect(body.registrationNumber).toBe(BRN)
    // 리뷰 NIT-5: 제목이 말하는 "개인이면 칸 없음"을 단언한다 — 개인으로 바꾸면 칸이 사라진다(보내는 값은 contentOf가 null — lib 시험).
    await user.click(screen.getByRole('radio', { name: '개인' }))
    expect(screen.queryByLabelText('법인등록번호')).toBeNull()
  })

  it('임시저장 with 구분·번호·상호 sends a DRAFT create with every key (optional values null, countryCode KR, empty lists), clears the number and hands the reference to onSaved (re-read)', async () => {
    const { api, onSaved, view } = renderCreate({ createBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await paste('사업자등록번호', BRN)
    await paste('상호', '임시 사업자')
    await press('임시저장')
    expect(api.createBusiness).toHaveBeenCalledWith({
      submissionId: sid(1), registrationStatus: 'DRAFT', registrationNumber: BRN, businessType: 'INDIVIDUAL', displayName: '임시 사업자',
      registration: { corporateRegistrationNumber: null, openingDate: null, countryCode: 'KR', englishName: null, phoneNumber: null, email: null },
      representatives: [], categories: [], addresses: [], contacts: [],
    })
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(BIZ))
    expect(field('사업자등록번호')).toHaveValue('')
    expect(view.container.innerHTML).not.toContain(BRN)
    // 리뷰 MINOR-3: 원문 번호는 생성 요청 본문에만(모든 대역 호출 인수 전수).
    expect(callsContaining(api, BRN)).toEqual(['createBusiness'])
    expect(leakedCalls(api, BRN)).toEqual([])
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    expect(storageIsEmpty()).toBe(true)
  })

  it('등록 완료 sends a REGISTERED create with the full content; the client rules block an incomplete REGISTERED form (no call, field errors)', async () => {
    const { api, onSaved } = renderCreate({ createBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await paste('사업자등록번호', BRN)
    await paste('상호', '데모 사업자')
    await press('등록 완료')
    expect(api.createBusiness).not.toHaveBeenCalled()
    expect(field('개업일')).toHaveAccessibleDescription('필수 입력입니다.')
    expect(field('사업체 전화')).toHaveAccessibleDescription('필수 입력입니다.')
    expect(screen.getByRole('alert')).toHaveTextContent('입력을 확인하세요')

    await user.clear(field('사업자등록번호'))
    await user.clear(field('상호'))
    await fillRegisteredIndividual()
    await press('등록 완료')
    expect(api.createBusiness).toHaveBeenCalledWith({ submissionId: sid(1), ...REGISTERED_BODY })
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(BIZ))
  })

  it('DRAFT minimum (D-BIZ-002): 구분 missing → field error and nothing sent', async () => {
    const { api } = renderCreate({ createBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    await paste('사업자등록번호', BRN)
    await paste('상호', '임시 사업자')
    await press('임시저장')
    expect(api.createBusiness).not.toHaveBeenCalled()
    expect(screen.getByRole('radiogroup', { name: '사업체 구분' })).toHaveAccessibleDescription('필수 입력입니다.')
  })

  it('중복 확인 (D-BIZ-003): uses the number lookup (digits only); an existing business shows 상호 · masked number + 상세 보기 (no biz_); the result is void as soon as the number changes', async () => {
    const existing = individualRegisteredDetail(1, '이미 있는 사업자')
    const lookup = vi.fn().mockResolvedValueOnce({ items: [lookupItemOf(existing)] }).mockResolvedValueOnce({ items: [] })
    const { api, onOpenBusiness, view } = renderCreate({ lookupBusinessByRegistrationNumber: lookup })
    await paste('사업자등록번호', BRN)
    await press('중복 확인')
    expect(lookup).toHaveBeenCalledWith(BRN)
    const result = await screen.findByRole('status', { name: '중복 확인 결과' })
    expect(result).toHaveTextContent('이미 등록된 사업자: 이미 있는 사업자 · 778-12-34567')
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
    await user.click(within(result).getByRole('button', { name: '기존 사업자 상세 보기' }))
    expect(onOpenBusiness).toHaveBeenCalledWith(BIZ)
    // 번호가 바뀌면 결과 즉시 무효(지침서 §3).
    await user.type(field('사업자등록번호'), '{Backspace}')
    expect(screen.queryByRole('status', { name: '중복 확인 결과' })).toBeNull()
    await user.type(field('사업자등록번호'), '0')
    await press('중복 확인')
    expect(await screen.findByRole('status', { name: '중복 확인 결과' })).toHaveTextContent('등록 가능')
    expect(screen.getByRole('status', { name: '중복 확인 결과' })).toHaveTextContent('저장 시 다시 확인합니다')
    // 리뷰 MINOR-3: 상세 보기·그 밖 호출 인수에 원문 번호 없음(번호 조회만).
    expect(leakedCalls(api, BRN)).toEqual([])
    expect(leakedCalls(api, '778123456')).toEqual([])
  })

  it('중복 확인 429 shows the shared BRN budget wording (Demo values); a malformed number is not looked up', async () => {
    const lookup = vi.fn(async () => { throw apiError(429, 'RATE_LIMITED') })
    renderCreate({ lookupBusinessByRegistrationNumber: lookup })
    await paste('사업자등록번호', '77812')
    await press('중복 확인')
    expect(lookup).not.toHaveBeenCalled()
    expect(field('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호는 숫자 10자리입니다.')
    await paste('사업자등록번호', '34560')
    await press('중복 확인')
    expect(await screen.findByText(/사업자등록번호 10회\/분/)).toHaveTextContent('Demo 값')
  })

  it('a duplicate on save (D-BIZ-003) shows the existing business name · masked number with 상세 보기; DOM has no biz_', async () => {
    const existing = individualRegisteredDetail(9, '먼저 있던 사업자')
    const { onOpenBusiness, onSaved, view } = renderCreate({
      createBusiness: vi.fn(async () => ({ ...commandRejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE'), existing: lookupItemOf(existing) })),
    })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await paste('사업자등록번호', BRN)
    await paste('상호', '임시 사업자')
    await press('임시저장')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    expect(alert).toHaveTextContent('먼저 있던 사업자 · 778-12-34567')
    await user.click(within(alert).getByRole('button', { name: '기존 사업자 상세 보기' }))
    expect(onOpenBusiness).toHaveBeenCalledWith(bizId(9))
    expect(onSaved).not.toHaveBeenCalled()
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('server fieldErrors map to their fields (incl. representatives[i].type and categories TOO_MANY); unknown paths are ignored by the client parser', async () => {
    const error = new ApiError(400, 'INVALID_INPUT', undefined, [
      { path: 'contacts[0].email', code: 'FORMAT' },
      { path: 'categories[1].name', code: 'DUPLICATE' },
      { path: 'representatives[0].type', code: 'REQUIRED' },
      { path: 'categories', code: 'TOO_MANY' },
      { path: 'addresses[0].postalCode', code: 'FORMAT' },
    ])
    renderCreate({ createBusiness: vi.fn(async () => { throw error }) })
    await fillRegisteredIndividual()
    await press('등록 완료')
    expect(await screen.findByRole('alert')).toHaveTextContent('입력을 확인하세요')
    expect(field('담당자 1 이메일')).toHaveAccessibleDescription('이메일 형식(예: name@example.com)이 아닙니다.')
    expect(field('종목 1')).toHaveAccessibleDescription('같은 이름이 이미 있습니다.')
    expect(field('대표자 1 유형')).toHaveAccessibleDescription('필수 입력입니다.')
    expect(field('사업장 우편번호')).toHaveAccessibleDescription('우편번호는 숫자 5자리입니다.')
    expect(region('사업 내용')).toHaveTextContent('개수 상한을 넘었습니다')
    // 입력을 고쳐 다시 보낼 수 있다(400은 효과 없음 확정 — 잠금 없음, 새 submissionId).
    expect(screen.getByRole('button', { name: '등록 완료' })).toBeEnabled()
  })

  it('a MASTER_OUTCOME_UNKNOWN locks the form; "같은 내용 다시 보내기" resends the same submissionId and body; the guidance mentions asking for help if it repeats', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockResolvedValueOnce(commandSucceeded(BIZ, true))
    const { onSaved } = renderCreate({ createBusiness: send })
    await fillRegisteredIndividual()
    await press('등록 완료')
    const group = await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(group).toHaveTextContent('처리되었는지 알 수 없습니다')
    expect(group).toHaveTextContent('반복되면 운영 담당에게 문의')
    expect(field('상호')).toBeDisabled()
    expect(screen.getByRole('button', { name: '등록 완료' })).toBeDisabled()
    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(BIZ))
    expect(send).toHaveBeenCalledTimes(2)
    expect(send.mock.calls[1]![0]).toEqual(send.mock.calls[0]![0])
    expect(send.mock.calls[0]![0].submissionId).toBe(sid(1))
  })

  it('BIZ-3 리뷰 3: distinct 503 wording — RETRY_SAME_KEY and AUDIT_UNAVAILABLE lock (resend), MASTER_BUSY does not lock; 409 IDEMPOTENCY_KEY_REUSED ends the attempt', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_RETRY_SAME_KEY'))
      .mockRejectedValueOnce(apiError(503, 'AUDIT_UNAVAILABLE'))
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockRejectedValueOnce(apiError(503, 'MASTER_BUSY'))
      .mockRejectedValueOnce(apiError(429, 'RATE_LIMITED'))
    renderCreate({ createBusiness: send })
    await user.click(screen.getByRole('radio', { name: '개인' }))
    await paste('사업자등록번호', BRN)
    await paste('상호', '임시 사업자')
    await press('임시저장')
    expect(await screen.findByRole('group', { name: '결과 확인 필요' })).toHaveTextContent('처리 결과를 아직 확정하지 못했습니다')
    await press('같은 내용 다시 보내기')
    await vi.waitFor(() => expect(screen.getByRole('group', { name: '결과 확인 필요' })).toHaveTextContent('감사 기록을 남기지 못해'))
    await press('같은 내용 다시 보내기')
    expect(await screen.findByText(/같은 제출 번호로 다른 내용/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(send.mock.calls.slice(0, 3).map((c) => c[0].submissionId)).toEqual([sid(1), sid(1), sid(1)])
    // 확정 실패 뒤 새 제출 = 새 submissionId. 번호는 보낸 뒤 비웠으므로 다시 입력한다.
    await paste('사업자등록번호', BRN)
    await press('임시저장')
    expect(await screen.findByText(/바빠 처리하지 않았습니다/)).toBeInTheDocument()
    expect(screen.queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(send.mock.calls[3]![0].submissionId).toBe(sid(2))
    await paste('사업자등록번호', BRN)
    await press('임시저장')
    expect(await screen.findByText(/저장 요청이 너무 잦습니다/)).toHaveTextContent('Demo 값')
  })
})

describe('BusinessProfileFormScreen — 수정·등록 완료 모드 (BIZ-4-2, §4.5·§8.3)', { timeout: 20_000 }, () => {
  it('the number is read-only (masked) with the TBD-04 notice and no 중복 확인; REGISTERED has 저장·취소; the update body has every key, version from the detail, reason null, no registrationNumber/registrationStatus', async () => {
    const detail = individualRegisteredDetail()
    const { api, onSaved, view } = renderEdit(detail, { updateBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    expect(screen.getByRole('heading', { name: '사업자 수정' })).toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '사업자등록번호' })).toBeNull()
    expect(screen.getByRole('group', { name: '사업자등록번호' })).toHaveTextContent('778-12-34567')
    expect(screen.getByText('사업자등록번호 변경은 정책 미정으로 지원하지 않습니다.')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '중복 확인' })).toBeNull()
    expect(within(screen.getByRole('group', { name: '저장' })).getAllByRole('button').map((b) => b.textContent)).toEqual(['저장', '취소'])
    expect(field('대표자 1 이름')).toHaveValue('홍길동')
    expect(field('사업체 전화')).toHaveValue('02-1234-5678')
    await replace('사업체 이메일(선택)', 'biz.demo@example.test')
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    await press('저장')
    expect(api.updateBusiness).toHaveBeenCalledTimes(1)
    const [businessId, body] = vi.mocked(api.updateBusiness).mock.calls[0]!
    expect(businessId).toBe(BIZ)
    expect(Object.keys(body).sort()).toEqual(['addresses', 'businessType', 'categories', 'contacts', 'displayName', 'reason', 'registration', 'representatives', 'submissionId', 'version'])
    expect(body).toMatchObject({ submissionId: sid(1), version: 3, reason: null, registration: { email: 'biz.demo@example.test', countryCode: 'KR' } })
    expect(body.addresses).toEqual([{ addressType: 'REGISTERED', postalCode: '04524', baseAddress: '서울특별시 중구 세종대로 110', detailAddress: '데모동 101호' }])
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(BIZ))
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('r-6 중요 변경 감지: 상호 change → 변경 사유 (required) appears; without a reason nothing is sent; with a reason it is sent', async () => {
    const { api } = renderEdit(individualRegisteredDetail(), { updateBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    await replace('상호', '데모 사업자 2')
    expect(field('변경 사유')).toHaveAccessibleDescription(/개인정보를 적지 마세요/)
    await press('저장')
    expect(api.updateBusiness).not.toHaveBeenCalled()
    expect(field('변경 사유')).toHaveAccessibleDescription('필수 입력입니다.')
    await paste('변경 사유', '상호 정정')
    await press('저장')
    expect(vi.mocked(api.updateBusiness).mock.calls[0]![1]).toMatchObject({ displayName: '데모 사업자 2', reason: '상호 정정' })
  })

  it('r-6: REGISTERED — adding a representative only, or swapping their order, needs a reason (M-2·M-4)', async () => {
    const first = renderEdit(individualRegisteredDetail())
    await user.selectOptions(field('대표자 1 유형'), 'JOINT')
    expect(screen.queryByLabelText('변경 사유')).not.toBeNull()
    await user.selectOptions(field('대표자 1 유형'), 'SOLE')
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    await press('대표자 추가')
    await paste('대표자 2 이름', '이몽룡')
    expect(field('변경 사유')).toBeInTheDocument()
    first.view.unmount()

    renderEdit(jointRegisteredDetail())
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    await replace('대표자 1 이름', '이몽룡')
    await replace('대표자 2 이름', '홍길동')
    expect(field('변경 사유')).toBeInTheDocument()
  })

  it('r-6: DRAFT 0 → 1 first representative and a 업태 order change need no reason', async () => {
    const first = renderEdit(draftMinimalDetail())
    await paste('대표자 1 이름', '홍길동')
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    first.view.unmount()

    const detail = individualRegisteredDetail()
    renderEdit({ ...detail, categories: [{ categoryType: 'BUSINESS_TYPE', name: '도소매' }, { categoryType: 'BUSINESS_TYPE', name: '서비스' }, { categoryType: 'BUSINESS_ITEM', name: '전자상거래' }] })
    await replace('업태 1', '서비스')
    await replace('업태 2', '도소매')
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
  })

  it('a server BUSINESS_CHANGE_REASON_REQUIRED (server is the authority) shows the reason field with the rejection', async () => {
    renderEdit(individualRegisteredDetail(), { updateBusiness: vi.fn(async () => commandRejected('BUSINESS_CHANGE_REASON_REQUIRED')) })
    await replace('사업체 이메일(선택)', 'biz.demo@example.test')
    await press('저장')
    expect(await screen.findByRole('alert')).toHaveTextContent('중요정보를 바꾸려면 변경 사유가 필요합니다')
    expect(field('변경 사유')).toBeInTheDocument()
  })

  it('a version conflict says so and offers 다시 불러오기 (re-read the detail); BUSINESS_PROFILE_UNCHANGED says nothing changed', async () => {
    const send = vi.fn().mockResolvedValueOnce(commandRejected('BUSINESS_VERSION_CONFLICT')).mockResolvedValueOnce(commandRejected('BUSINESS_PROFILE_UNCHANGED'))
    const { onReload } = renderEdit(individualRegisteredDetail(), { updateBusiness: send })
    await replace('사업체 이메일(선택)', 'biz.demo@example.test')
    await press('저장')
    expect(await screen.findByRole('alert')).toHaveTextContent('다른 사용자가 먼저 수정했습니다')
    await press('다시 불러오기')
    expect(onReload).toHaveBeenCalledTimes(1)
    await press('저장')
    expect(await screen.findByText(/바뀐 내용이 없습니다/)).toBeInTheDocument()
  })

  it('DRAFT: 임시저장 updates, 등록 완료 completes the registration with the same body shape; the legacy DRAFT (구분 NULL — L-7) must pick a 구분 first', async () => {
    const detail = legacyDraftDetail()
    const { api, onSaved } = renderEdit(detail, {
      completeBusinessRegistration: vi.fn(async () => commandSucceeded(bizId(3))),
      updateBusiness: vi.fn(async () => commandSucceeded(bizId(3))),
    })
    expect(screen.getByRole('heading', { name: '사업자 이어서 입력' })).toBeInTheDocument()
    expect(within(screen.getByRole('group', { name: '저장' })).getAllByRole('button').map((b) => b.textContent)).toEqual(['임시저장', '등록 완료', '취소'])
    await press('임시저장')
    expect(api.updateBusiness).not.toHaveBeenCalled()
    expect(screen.getByRole('radiogroup', { name: '사업체 구분' })).toHaveAccessibleDescription('필수 입력입니다.')

    await user.click(screen.getByRole('radio', { name: '개인' }))
    await paste('개업일', '2026-01-01')
    await paste('업태 1', '도소매')
    await paste('종목 1', '전자상거래')
    await paste('사업장 우편번호', '04524')
    await paste('사업장 기본주소', '서울특별시 중구 세종대로 110')
    await paste('대표자 1 이름', '홍길동')
    await paste('사업체 전화', '0212345678')
    await paste('담당자 1 이름', '김담당')
    await paste('담당자 1 이메일', 'contact.demo@example.test')
    await paste('담당자 1 전화', '01012345678')
    await press('등록 완료')
    expect(api.completeBusinessRegistration).toHaveBeenCalledTimes(1)
    const [businessId, body] = vi.mocked(api.completeBusinessRegistration).mock.calls[0]!
    expect(businessId).toBe(bizId(3))
    const content: Partial<typeof REGISTERED_BODY> = { ...REGISTERED_BODY }
    delete content.registrationNumber
    delete content.registrationStatus
    expect(body).toEqual({ submissionId: sid(1), version: 0, reason: null, ...content, displayName: 'DEMO 옛 사업자' })
    await vi.waitFor(() => expect(onSaved).toHaveBeenCalledWith(bizId(3)))
  })

  it('review NIT-2 (§4.5 "일반정보만이면 사유 선택"): a general change shows an optional reason; empty → null, typed → sent', async () => {
    const { api } = renderEdit(individualRegisteredDetail(), { updateBusiness: vi.fn(async () => commandSucceeded(BIZ)) })
    expect(screen.queryByLabelText('변경 사유(선택)')).toBeNull()
    await replace('사업체 이메일(선택)', 'biz.demo@example.test')
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    await paste('변경 사유(선택)', '연락처 갱신')
    await press('저장')
    expect(vi.mocked(api.updateBusiness).mock.calls[0]![1]).toMatchObject({ reason: '연락처 갱신', registration: { email: 'biz.demo@example.test' } })
  })

  it('review NIT-1: field errors without a field — contacts[i].primary goes to the 담당자 group, version gives a general message, reason force-shows the reason field', async () => {
    const send = vi.fn()
      .mockRejectedValueOnce(new ApiError(400, 'INVALID_INPUT', undefined, [{ path: 'contacts[0].primary', code: 'PRIMARY_MULTIPLE' }, { path: 'version', code: 'FORMAT' }]))
      .mockRejectedValueOnce(new ApiError(400, 'INVALID_INPUT', undefined, [{ path: 'reason', code: 'TOO_LONG' }]))
    renderEdit(individualRegisteredDetail(), { updateBusiness: send })
    await press('저장')
    expect(await screen.findByRole('alert')).toHaveTextContent('화면 칸에 표시할 수 없는 오류')
    expect(screen.getByRole('region', { name: '등록 담당자' })).toHaveTextContent('기본 등록 담당자는 1명만 고를 수 있습니다')
    await press('저장')
    expect(await screen.findByLabelText('변경 사유')).toHaveAccessibleDescription('200자 이하로 입력하세요.')
  })
})

