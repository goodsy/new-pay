import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { BusinessesScreen, openBusinessIn } from './BusinessesScreen'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'
import type { BusinessDetail } from '../api/adminApi'
import { createScreenMemory, hasUnresolvedRequest } from '../lib/screenMemory'
import { domOccurrences } from '../test/domScan'
import { fakeApi } from '../test/fakeApi'
import { commandRejected, individualRegisteredDetail, listItemOf, lookupItemOf } from '../test/businessFixtures'
import { CHANGE_TYPE_LABELS, REGISTRATION_STATUS_LABELS } from '../lib/businessProfile'
import { REJECTION_MESSAGES } from '../components/RegistrationStep'
import { AUDIT_ACTION_LABELS } from '../lib/labels'
import { MERCHANT_CHANGE_TYPE_LABELS } from '../lib/merchantChangeTypes'

/**
 * BIZ-NORM-002(GOV-BIZ-001 M-3·M-7, D-GOV-008 ①~④, D-FLOW-006 — 기대값은 결정·BIZ.md §0 용어 원문 리터럴):
 * - M-3(BR-BIZ-311): 사용중지(INACTIVE) 사업자 상세는 편집 폼을 열지 않고 읽기 표시 + "사용중지된 사업자는 수정할 수 없습니다". MASTER 거절 `BUSINESS_INACTIVE`는
 *   효과 없음 확정 업무 거절 — 결과 불명 잠금이 아니다. 결과 확인이 필요한 편집이 이 사업자 것이면 폼을 유지한다(같은 submissionId 재전송 — ADMIN-DS §7.1 금융 화면 동작).
 * - M-7: 사유 칸 이름 '사유 및 비고' / '사유 및 비고(선택)'(동작·필수 규칙 불변).
 * - 용어 화면 문구: 저장 코드값·API 필드·감사 action code는 그대로, 표시 문구만.
 * 시험 값은 명시 Demo fixture.
 */
const user = userEvent.setup({ delay: null })
const REG = individualRegisteredDetail(1)
const INACTIVE: BusinessDetail = { ...REG, business: { ...REG.business, registrationStatus: 'INACTIVE' } }
const pageOf = (details: BusinessDetail[]) => ({ items: details.map(listItemOf), next: null })

function renderList(detail: BusinessDetail, canEdit = true, memory = createScreenMemory(), overrides: Parameters<typeof fakeApi>[0] = {}) {
  const api = fakeApi({ searchBusinesses: vi.fn(async () => pageOf([detail])), businessDetail: vi.fn(async () => detail), ...overrides })
  let n = 0
  const view = render(<BusinessesScreen api={api} memory={memory} canEdit={canEdit} onContinue={vi.fn()} handoffState={() => 'empty'} onOpenMerchant={vi.fn()}
    newSubmissionId={() => (++n).toString(16).padStart(32, '0')} />)
  return { api, view, memory }
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

describe('BIZ-NORM-002 M-3 — 사용중지 사업자는 수정할 수 없다(BR-BIZ-311)', () => {
  it('OPERATOR가 사용중지 사업자 상세를 열면 편집 폼 없이 읽기 표시 + 안내; 배지 "사용중지"; 입력칸·저장 0, 위쪽 목록으로; style·내부 키 0', async () => {
    const { view } = renderList(INACTIVE)
    const section = await openDetail()
    await within(section).findByText('사업자등록번호')
    expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull()
    expect(within(section).getByText('사용중지된 사업자는 수정할 수 없습니다.')).toBeInTheDocument()
    expect(within(section).getByText('사용중지')).toBeInTheDocument()
    const info = document.getElementById(screen.getByRole('tab', { name: '기본정보' }).getAttribute('aria-controls')!)!
    expect(info.querySelectorAll('input, select, textarea')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull()
    expect(screen.queryByRole('button', { name: '이 사업자로 가맹점 등록' })).toBeNull()
    expect(screen.getByRole('button', { name: '목록으로' })).toBeInTheDocument()
    expect(view.container.querySelectorAll('[style]')).toHaveLength(0)
    expect(domOccurrences(view.container, 'biz_')).toEqual([])
  })

  it('대조: 등록 완료 사업자는 그대로 편집 폼(안내 없음)', async () => {
    renderList(REG)
    await openDetail()
    expect(await screen.findByRole('region', { name: '사업자 정보 편집' })).toBeInTheDocument()
    expect(screen.queryByText('사용중지된 사업자는 수정할 수 없습니다.')).toBeNull()
  })

  it('조회 역할(편집 권한 없음): 사용중지 사업자도 읽기 표시 — 편집 폼 없음, 사용중지 안내 없음(원래 폼이 없으므로)', async () => {
    renderList(INACTIVE, false)
    const section = await openDetail()
    await within(section).findByText('사업자등록번호')
    expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull()
    expect(screen.queryByText(/사용중지된 사업자는 수정할 수 없습니다/)).toBeNull()
    expect(screen.queryByRole('button', { name: '저장' })).toBeNull()
  })

  it('저장 중 사용중지됐을 때 MASTER 거절 BUSINESS_INACTIVE: 효과 없음 확정 거절 문구, 결과 불명 잠금 아님(다시 보내기 없음, 미해결 요청 없음)', async () => {
    const memory = createScreenMemory()
    const updateBusiness = vi.fn(async () => commandRejected('BUSINESS_INACTIVE'))
    renderList(REG, true, memory, { updateBusiness })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'biz.demo@example.test')
    await user.click(screen.getByRole('button', { name: '저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('사용중지된 사업자는 수정할 수 없습니다')
    expect(updateBusiness).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: '같은 내용 다시 보내기' })).toBeNull()
    expect(hasUnresolvedRequest(memory)).toBe(false)
  })

  it('결과 확인이 필요한 이 사업자 편집이 있으면(보낸 뒤 결과 불명) 사용중지로 다시 읽혀도 폼을 유지한다 — 같은 submissionId 재전송 경로 보존', async () => {
    const memory = createScreenMemory()
    const updateBusiness = vi.fn().mockRejectedValueOnce(new Error('network')).mockResolvedValue(commandRejected('BUSINESS_INACTIVE', true))
    const first = renderList(REG, true, memory, { updateBusiness })
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'biz.demo@example.test')
    await user.click(screen.getByRole('button', { name: '저장' }))
    await screen.findByRole('button', { name: '같은 내용 다시 보내기' })
    expect(hasUnresolvedRequest(memory)).toBe(true)
    first.view.unmount()
    // 다른 화면에서 돌아와 상세를 다시 읽으니 그 사이 사용중지됨.
    openBusinessIn(memory, REG.business.businessId)
    const reread = vi.fn(async () => INACTIVE)
    renderList(INACTIVE, true, memory, { updateBusiness, businessDetail: reread })
    const resend = await screen.findByRole('button', { name: '같은 내용 다시 보내기' })
    expect(reread).toHaveBeenCalled()
    // 사용중지 + 결과 확인이 필요한 편집: 폼은 남고 안내가 그 사정을 알린다.
    expect(screen.getByRole('region', { name: '사업자 정보 편집' })).toBeInTheDocument()
    expect(screen.getByText('사용중지된 사업자는 수정할 수 없습니다. 결과 확인 뒤 읽기 표시로 바뀝니다.')).toBeInTheDocument()
    await user.click(resend)
    await vi.waitFor(() => expect(updateBusiness).toHaveBeenCalledTimes(2))
    expect(updateBusiness.mock.calls[0]![1]).toEqual(updateBusiness.mock.calls[1]![1])
    // 결과가 확정(업무 거절)되면 잠금이 풀리고 사용중지 사업자는 읽기 표시 + 안내.
    await vi.waitFor(() => expect(hasUnresolvedRequest(memory)).toBe(false))
    const section = screen.getByRole('region', { name: '사업자 상세' })
    await vi.waitFor(() => expect(screen.queryByRole('region', { name: '사업자 정보 편집' })).toBeNull())
    expect(within(section).getByText('사용중지된 사업자는 수정할 수 없습니다.')).toBeInTheDocument()
    expect(within(section).queryByText(/결과 확인 뒤 읽기 표시로 바뀝니다/)).toBeNull()
    const info = document.getElementById(screen.getByRole('tab', { name: '기본정보' }).getAttribute('aria-controls')!)!
    expect(info.querySelectorAll('input, select, textarea')).toHaveLength(0)
    expect(screen.queryByRole('button', { name: '같은 내용 다시 보내기' })).toBeNull()
  })
})

describe('BIZ-NORM-002 M-7 — 사유 칸 이름(D-FLOW-006)', () => {
  it("중요정보 변경이면 '사유 및 비고'(필수), 일반정보만이면 '사유 및 비고(선택)' — '변경 사유' 이름 없음", async () => {
    renderList(REG)
    await openDetail()
    await screen.findByRole('region', { name: '사업자 정보 편집' })
    await paste('사업체 이메일(선택)', 'biz.demo@example.test')
    expect(screen.getByLabelText('사유 및 비고(선택)')).toBeInTheDocument()
    await paste('상호', '새 상호')
    expect(screen.getByLabelText('사유 및 비고')).toBeInTheDocument()
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    expect(screen.queryByLabelText('변경 사유(선택)')).toBeNull()
  })
})

describe('BIZ-NORM-002 용어 화면 문구(D-GOV-008, BIZ.md §0)', () => {
  it('① 등록 상태·거절 문구: 사용중지 / 종료된 사업자 / 등록 완료할 수 없는 상태(사실 그대로) / BUSINESS_INACTIVE', () => {
    expect(REGISTRATION_STATUS_LABELS).toEqual({ DRAFT: '임시저장', REGISTERED: '등록 완료', INACTIVE: '사용중지' })
    expect(REJECTION_MESSAGES.BUSINESS_NOT_ACTIVE).toBe('종료된 사업자입니다')
    expect(REJECTION_MESSAGES.BUSINESS_NOT_DRAFT).toBe('등록 완료할 수 없는 상태입니다(이미 등록 완료됐거나 사용중지된 사업자)')
    expect(REJECTION_MESSAGES.BUSINESS_INACTIVE).toBe('사용중지된 사업자는 수정할 수 없습니다')
    expect(REJECTION_MESSAGES.BUSINESS_CHANGE_REASON_REQUIRED).toBe("중요정보를 바꾸려면 '사유 및 비고'에 사유를 적어야 합니다")
  })

  it("② 감사·이력 '생성' → '사업자 등록(저장)' (action·changeType code 그대로)", () => {
    expect(CHANGE_TYPE_LABELS.BusinessCreated).toBe('사업자 등록(저장)')
    expect(MERCHANT_CHANGE_TYPE_LABELS.BusinessCreated).toBe('사업자 등록(저장)')
    expect(AUDIT_ACTION_LABELS.BUSINESS_CREATE).toBe('사업자 등록(저장)')
  })

  it("② 목록 조건·상세 메타 '등록일' → '최초 저장일'", async () => {
    renderList(REG, false)
    const form = await screen.findByRole('form', { name: '사업자 검색' })
    expect(within(form).getByRole('group', { name: '최초 저장일' })).toContainElement(screen.getByLabelText('최초 저장일 시작'))
    expect(within(form).getByRole('group', { name: '최초 저장일' })).toContainElement(screen.getByLabelText('최초 저장일 끝'))
    expect(within(form).queryByText('등록일')).toBeNull()
    const section = await openDetail()
    await within(section).findByText('사업자등록번호')
    expect(within(section).getByText('최초 저장일')).toBeInTheDocument()
  })

  it("③ 번호 조회: 목록·가맹점 쪽 감사 안내 '번호 조회', 감사 라벨에 '번호 검색' 없음", async () => {
    renderList(REG, false)
    await screen.findByRole('form', { name: '사업자 검색' })
    expect(screen.getByLabelText('사업자등록번호')).toHaveAccessibleDescription('사업자등록번호 조회는 매번 감사 기록에 남습니다. 번호로 찾으면 다른 조건은 쓰지 않습니다.')
    expect(Object.values(AUDIT_ACTION_LABELS).filter((l) => l.includes('사업자') && /검색|번호 확인|중복\s?확인/.test(l))).toEqual([])
    expect(AUDIT_ACTION_LABELS.MERCHANT_BRN_SEARCHED).toBe('사업자등록번호 조회(가맹점 목록)')
  })

  it("③ 등록 1단계: '등록 여부 확인' 폼·버튼·결과", async () => {
    const api = fakeApi({ lookupBusinessByRegistrationNumber: vi.fn(async () => ({ items: [lookupItemOf(REG)] })) })
    render(<BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={createScreenMemory()} workspace onSaved={vi.fn()} onOpenBusiness={vi.fn()} onBack={vi.fn()}
      onSessionEnded={vi.fn()} newSubmissionId={() => '0'.repeat(32)} />)
    const form = screen.getByRole('form', { name: '등록 여부 확인' })
    await user.click(within(form).getByLabelText('사업자등록번호'))
    await user.paste('7781234567')
    await user.click(within(form).getByRole('button', { name: '등록 여부 확인' }))
    expect(await screen.findByRole('status', { name: '등록 여부 확인 결과' })).toHaveTextContent('이미 등록한 사업자입니다.')
    expect(screen.getByText(/먼저 등록 여부 확인으로/)).toBeInTheDocument()
  })

  it("④ 등록 담당자: 폼 칸 '등록 담당자 n …'·라디오 '기본 등록 담당자'·추가 버튼; 읽기 표시 줄 '등록 담당자 n (기본 등록 담당자)'; 섹션 제목 '담당자' 유지(D-ADMIN-UI-010 ⑥)", async () => {
    renderList(REG)
    await openDetail()
    const form = await screen.findByRole('region', { name: '사업자 정보 편집' })
    expect(within(form).getByRole('region', { name: '담당자' })).toBeInTheDocument()
    expect(within(form).getByLabelText('등록 담당자 1 이름')).toHaveValue(REG.contacts[0]!.name)
    expect(within(form).getByLabelText('등록 담당자 1 이메일')).toBeInTheDocument()
    expect(within(form).getByLabelText('등록 담당자 1 전화')).toBeInTheDocument()
    expect(within(form).getByRole('radio', { name: '등록 담당자 1 기본 등록 담당자' })).toBeChecked()
    expect(within(form).getByText('기본 등록 담당자')).toBeInTheDocument()
    expect(within(form).getByRole('button', { name: '등록 담당자 추가' })).toBeInTheDocument()
    expect(within(form).queryByText('기본 담당자')).toBeNull()
  })

  it('④ 읽기 표시(조회 역할) 줄 이름', async () => {
    renderList(REG, false)
    const section = await openDetail()
    await within(section).findByText('사업자등록번호')
    expect(within(section).getByText('등록 담당자 1 (기본 등록 담당자)')).toBeInTheDocument()
    expect(within(section).getByRole('heading', { name: '담당자' })).toBeInTheDocument()
  })
})
