import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from '../App'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'
import { ActionButton } from '../components/ActionButton'
import type { AdminApi, BusinessCommandOutcome, BusinessLookupItem } from '../api/adminApi'
import { createScreenMemory } from '../lib/screenMemory'
import { apiError, fakeApi } from '../test/fakeApi'
import { domOccurrences } from '../test/domScan'
import { commandRejected, individualRegisteredDetail, lookupItemOf } from '../test/businessFixtures'

/**
 * ADMIN-UI-WORKSPACE-001 사업자·가맹점 › 사업자 등록("모양만"): 참고 시안 사업자 등록 양식(카드 제목 줄 · 본문 · .btn/.btn.primary)을 실제 사업자 등록 폼(섹션 1~6)에 입힌다.
 * 사업자 조회 상세 안의 수정 폼(같은 구성요소, workspace 표시 없음)은 이번 범위 밖 — 지금 모양 그대로. 동작은 BusinessProfileFormScreen.test.tsx가 그대로 지킨다.
 * 명시 Demo Fixture: 시험 입력 전용 사업자등록번호(실제 번호 아님), 기존 사업자 1곳(데모 사업자 · 778*******).
 */
const BRN = '7781234560'
const user = userEvent.setup({ delay: null })
const shape = (el: Element) => Array.from(el.children).map((c) => [c.tagName.toLowerCase(), ...Array.from(c.classList)].join('.'))
const SECTIONS = ['사업자 기본', '사업 내용', '주소', '대표자', '사업체 연락', '등록 담당자']
const paste = async (label: string, value: string) => {
  await user.click(screen.getByLabelText(label))
  await user.paste(value)
}
const fillDraft = async () => {
  await user.click(screen.getByRole('radio', { name: '개인' }))
  await paste('사업자등록번호', BRN)
  await paste('상호', '데모 사업자')
}
const saveButtons = () => within(screen.getByRole('group', { name: '저장' })).getAllByRole('button')
function renderWorkspace(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi(overrides)
  const view = render(<BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={createScreenMemory()} workspace onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
  return { api, view }
}

describe('ADMIN-UI-WORKSPACE-001: 사업자·가맹점 › 사업자 등록 in the reference layout', { timeout: 20_000 }, () => {
  it('flush in the menu card: .mapnote(h2) → .cardnote(필수 안내) → form(sections .cardtop h3 + .cardbody · 저장 줄 .cardbody); buttons .btn/.btn.primary', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
    })
    render(<App api={api} />)
    await user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
    await user.click(screen.getByRole('tab', { name: '사업자 등록' }))
    const panel = screen.getByRole('main').querySelector(':scope > section.card > [role="tabpanel"]')!
    expect(shape(panel)).toEqual(['div.mapnote', 'div.cardnote', 'form'])
    const mapnote = panel.querySelector('.mapnote') as HTMLElement
    expect(within(mapnote).getByRole('heading', { level: 2, name: '사업자 등록' })).toBeInTheDocument()
    expect(mapnote).toHaveTextContent('사업자 기본정보를 입력합니다. 임시저장 뒤 이어서 입력할 수 있습니다.')
    expect(panel.querySelector(':scope > .cardnote > p.subtle')).toHaveTextContent('임시저장은 사업체 구분·사업자등록번호·상호만 있으면 됩니다.')
    expect(panel.querySelector(':scope > .cardnote > p.subtle')).toHaveTextContent('등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.')

    const form = panel.querySelector(':scope > form')!
    // fieldset(여백 0 utility)·저장 줄 .cardbody(오른쪽 정렬 utility items-end)에는 utility가 붙는다 — 태그와 시안 class만 본다(green1·green2 시험 자체 오류 수정).
    expect(Array.from(form.children).map((c) => c.tagName.toLowerCase())).toEqual(['fieldset', 'div'])
    expect(form.lastElementChild).toHaveClass('cardbody')
    const fieldset = form.querySelector(':scope > fieldset')!
    expect(shape(fieldset)).toEqual(Array(6).fill('section'))
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(SECTIONS)
    SECTIONS.forEach((name, i) => {
      const section = screen.getByRole('region', { name })
      expect(shape(section).map((s) => s.split('.').slice(0, 2).join('.'))).toEqual(['div.cardtop', 'div.cardbody'])
      expect(section.querySelector(':scope > .cardtop > h3')).toHaveTextContent(`${i + 1}. ${name}`)
    })
    expect(screen.getByRole('button', { name: '중복 확인' })).toHaveClass('btn')
    for (const name of ['업태 추가', '종목 추가', '대표자 추가', '담당자 추가', '업태 1 삭제', '대표자 1 삭제', '담당자 1 삭제']) {
      expect(screen.getByRole('button', { name })).toHaveClass('btn')
      expect(screen.getByRole('button', { name })).not.toHaveClass('primary')
    }

    // 저장 줄: D-BIZ-005 안내 p.subtle + 저장 group(임시저장 .btn · 등록 완료 .btn.primary · 취소 .btn).
    const bar = form.querySelector(':scope > .cardbody') as HTMLElement
    expect(bar.querySelector(':scope > p.subtle')).toHaveTextContent('사업자등록번호는 저장 후 변경할 수 없습니다.')
    expect(saveButtons().map((b) => b.textContent)).toEqual(['임시저장', '등록 완료', '취소'])
    // 기대값 변경(reviewFix1 NIT-3 — 이번 화면 새 시험만): 시안 버튼에도 shadcn Button처럼 줄바꿈·줄어듦 막음(whitespace-nowrap shrink-0).
    expect(saveButtons().map((b) => b.className)).toEqual(['btn whitespace-nowrap shrink-0', 'btn whitespace-nowrap shrink-0 primary', 'btn whitespace-nowrap shrink-0'])
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
  })

  it('visible busy cues: 중복 확인 "확인 중…" and 저장 "저장 중…" (polite live text; button names and the draft body unchanged)', async () => {
    let finishLookup: (v: { items: BusinessLookupItem[] }) => void = () => {}
    let finishCreate: (v: BusinessCommandOutcome) => void = () => {}
    const { api } = renderWorkspace({
      lookupBusinessByRegistrationNumber: vi.fn(() => new Promise<{ items: BusinessLookupItem[] }>((resolve) => { finishLookup = resolve })),
      createBusiness: vi.fn(() => new Promise<BusinessCommandOutcome>((resolve) => { finishCreate = resolve })),
    })
    await fillDraft()
    const check = screen.getByRole('button', { name: '중복 확인' })
    const checkLive = check.parentElement!.querySelector(':scope > span.subtle')!
    expect(checkLive).toHaveAttribute('aria-live', 'polite')
    expect(checkLive).toBeEmptyDOMElement()
    await user.click(check)
    expect(checkLive).toHaveTextContent('확인 중…')
    expect(screen.getByRole('button', { name: '중복 확인' })).toHaveAttribute('aria-busy', 'true')
    finishLookup({ items: [] })
    expect(await screen.findByRole('status', { name: '중복 확인 결과' })).toHaveTextContent('등록 가능')
    expect(checkLive).toBeEmptyDOMElement()

    const saveLive = screen.getByRole('group', { name: '저장' }).parentElement!.querySelector(':scope > span.subtle')!
    expect(saveLive).toHaveAttribute('aria-live', 'polite')
    expect(saveLive).toBeEmptyDOMElement()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    expect(saveLive).toHaveTextContent('저장 중…')
    expect(saveButtons().map((b) => b.textContent)).toEqual(['임시저장', '등록 완료', '취소'])
    expect(api.createBusiness).toHaveBeenCalledWith(expect.objectContaining({ registrationStatus: 'DRAFT', registrationNumber: BRN, displayName: '데모 사업자' }))
    finishCreate(commandRejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE'))
    expect(await screen.findByRole('alert')).toHaveTextContent('이미 등록된 사업자등록번호입니다')
    expect(saveLive).toBeEmptyDOMElement()
  })

  it('notices sit in a .cardnote under the form: duplicate rejection with 기존 사업자 상세 보기 .btn; result unknown buttons (.btn.primary · .btn · destructive-solid)', async () => {
    const existing = lookupItemOf(individualRegisteredDetail(1))
    const { view } = renderWorkspace({
      createBusiness: vi.fn(async () => ({ ...commandRejected('BUSINESS_REGISTRATION_NUMBER_DUPLICATE'), existing })),
    })
    await fillDraft()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    const alert = await screen.findByRole('alert')
    const note = alert.closest('.cardnote')!
    expect(note.parentElement).toBe(view.container)
    expect(view.container.lastElementChild).toBe(note)
    expect(within(alert).getByRole('button', { name: '기존 사업자 상세 보기' })).toHaveClass('btn')
    expect(domOccurrences(document.body, 'biz_')).toEqual([])
    view.unmount()

    renderWorkspace({ createBusiness: vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') }) })
    await fillDraft()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    const group = await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(group.parentElement).toHaveClass('cardnote')
    expect(within(group).getByRole('button', { name: '같은 내용 다시 보내기' })).toHaveClass('btn', 'primary')
    expect(within(group).getByRole('button', { name: '취소하고 새로 입력' })).toHaveClass('btn')
    expect(within(group).getByRole('button', { name: '취소하고 새로 입력' })).not.toHaveClass('primary')
    await user.click(within(group).getByRole('button', { name: '취소하고 새로 입력' }))
    expect(within(group).getByRole('button', { name: '그래도 새로 입력' })).toHaveClass('btn', 'border-destructive-solid', 'bg-destructive-solid', 'text-white')
  })

  it('reviewFix1 MINOR-2: result unknown → 같은 내용 다시 보내기 resends the same submissionId and identical body; 그래도 새로 입력 unlocks the form', async () => {
    const createBusiness = vi.fn(async () => { throw apiError(503, 'MASTER_OUTCOME_UNKNOWN') })
    renderWorkspace({ createBusiness })
    await fillDraft()
    await user.click(screen.getByRole('button', { name: '임시저장' }))
    const group = await screen.findByRole('group', { name: '결과 확인 필요' })
    const fieldset = screen.getByRole('region', { name: '사업자 기본' }).closest('fieldset')!
    expect(fieldset).toBeDisabled()
    expect(createBusiness).toHaveBeenCalledTimes(1)
    const first = (createBusiness.mock.calls[0] as unknown[])[0] as { submissionId: string }
    expect(first).toEqual(expect.objectContaining({ registrationStatus: 'DRAFT', registrationNumber: BRN, displayName: '데모 사업자' }))
    await user.click(within(group).getByRole('button', { name: '같은 내용 다시 보내기' }))
    await vi.waitFor(() => expect(createBusiness).toHaveBeenCalledTimes(2))
    expect((createBusiness.mock.calls[1] as unknown[])[0]).toEqual(first)
    expect(((createBusiness.mock.calls[1] as unknown[])[0] as { submissionId: string }).submissionId).toBe(first.submissionId)
    const still = await screen.findByRole('group', { name: '결과 확인 필요' })
    expect(fieldset).toBeDisabled()
    await user.click(within(still).getByRole('button', { name: '취소하고 새로 입력' }))
    await user.click(within(still).getByRole('button', { name: '그래도 새로 입력' }))
    expect(screen.queryByRole('group', { name: '결과 확인 필요' })).toBeNull()
    expect(fieldset).toBeEnabled()
  })

  it('reviewFix1 NIT-4: the workspace ActionButton defaults to type="button" (a caller-given type wins)', () => {
    render(<form><ActionButton workspace>기본</ActionButton><ActionButton workspace type="submit">제출</ActionButton></form>)
    expect(screen.getByRole('button', { name: '기본' })).toHaveAttribute('type', 'button')
    expect(screen.getByRole('button', { name: '제출' })).toHaveAttribute('type', 'submit')
  })

  it('without the workspace flag (사업자 조회 상세 안의 수정 폼 — 이번 범위 밖) the form keeps the card look', () => {
    const { container } = render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'edit', detail: individualRegisteredDetail(1) }} onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
    expect(container.querySelector('.mapnote')).toBeNull()
    expect(container.querySelector('.cardtop')).toBeNull()
    expect(container.querySelectorAll('[data-slot="card"]')).toHaveLength(6)
  })
})
