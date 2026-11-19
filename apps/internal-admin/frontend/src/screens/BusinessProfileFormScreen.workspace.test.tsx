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
import { readFileSync } from 'node:fs'
import { DetailRow, InfoCard } from '../components/DetailCard'

/**
 * ADMIN-UI-WORKSPACE-001 사업자·가맹점 › 사업자 등록("모양만"): 참고 시안 사업자 등록 양식(카드 제목 줄 · 본문 · .btn/.btn.primary)을 실제 사업자 등록 폼(섹션 1~6)에 입힌다.
 * 사업자 조회 상세 안의 수정 폼(같은 구성요소, workspace 표시 없음)은 LAYOUT-ROLLOUT B5부터 같은 폼 줄·섹션 카드(껍데기만 다름 — 아래 B5 describe). 동작은 BusinessProfileFormScreen.test.tsx가 그대로 지킨다.
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
const WORKSPACE_CSS = readFileSync('src/workspace.css', 'utf8') // Vitest 작업 디렉터리 = frontend/
/** 폼 줄(사용자 결정 2026-11-19): 입력이 든 .fieldrow — 첫 자식 label(for = 입력 id, 이름 왼쪽), 둘째 .fieldcontrol(입력 + 줄 끝 버튼). */
const rowOf = (input: HTMLElement) => input.closest('.fieldrow') as HTMLElement
const saveButtons = () => within(screen.getByRole('group', { name: '저장' })).getAllByRole('button')
function renderWorkspace(overrides: Partial<AdminApi> = {}) {
  const api = fakeApi(overrides)
  const view = render(<BusinessProfileFormScreen api={api} mode={{ kind: 'create' }} memory={createScreenMemory()} workspace onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
  return { api, view }
}

describe('ADMIN-UI-WORKSPACE-001: 사업자·가맹점 › 사업자 등록 in the reference layout', { timeout: 20_000 }, () => {
  it('flush in the menu card: .mapnote(h2) → .cardnote(필수 안내) → form(번호 섹션 카드 h3 "n. 이름" · 폼 줄 · 저장 줄); buttons .btn/.btn.primary', async () => {
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_01JC00000000000000000000V1', displayName: '운영자', roles: ['OPERATOR'] })),
      approvals: vi.fn(async () => ({ approvals: [] })),
    })
    render(<App api={api} />)
    await user.click(within(await screen.findByRole('navigation', { name: '메뉴' })).getByRole('button', { name: '사업자·가맹점' }))
    // D-ADMIN-UI-006 기대값 변경(LAYOUT-ROLLOUT B1): 사업자 등록은 탭이 아니라 '사업자' 탭 목록의 '＋ 사업자 등록' 버튼으로 연다(같은 탭 패널 — 아래 구조 단언 그대로).
    await user.click(screen.getByRole('tab', { name: '사업자' }))
    await user.click(await screen.findByRole('button', { name: '＋ 사업자 등록' }))
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
    // 기대값 변경(사용자 지시 2026-11-19 — 사업자 조회 상세와 같은 문서 모양): 섹션 카드는 .cardbody 안 lg 2열 grid(DetailInfo 'grid gap-4 lg:grid-cols-2'),
    // 각 섹션은 InfoCard와 같은 카드(같은 class)·h3 `n. 이름`(text-sm font-semibold) + 폼 줄 묶음. 시안 .cardtop/.cardbody 카드 제목 줄 단언을 대체(구조 단언 강도 같음).
    expect(shape(fieldset)).toEqual(['div.cardbody.grid.gap-4.lg:grid-cols-2'])
    expect(shape(fieldset.firstElementChild!)).toEqual(Array(6).fill('section.flex.flex-col.gap-1.rounded-xl.border.bg-card.px-4.py-3'))
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(SECTIONS)
    SECTIONS.forEach((name, i) => {
      const section = screen.getByRole('region', { name })
      expect(shape(section)).toEqual(['h3.text-sm.font-semibold', 'div.flex.flex-col'])
      expect(section.querySelector(':scope > h3')).toHaveTextContent(`${i + 1}. ${name}`)
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

  it('userFeedback1-1: 중복 확인 sits on the 사업자등록번호 input row (wrapping, input-height button); help text and the result box stay below the row', async () => {
    renderWorkspace({ lookupBusinessByRegistrationNumber: vi.fn(async () => ({ items: [] })) })
    const input = screen.getByLabelText('사업자등록번호')
    const check = screen.getByRole('button', { name: '중복 확인' })
    const row = check.parentElement!
    expect(row.contains(input)).toBe(true)
    // 사용자 결정(2026-11-19) 폼 줄로 바뀜: 버튼은 입력과 같은 .fieldcontrol(입력 바로 오른쪽, 좁으면 줄바꿈 — CSS). 이번 피드백 새 시험만 기대값 변경.
    expect(row).toHaveClass('fieldcontrol')
    expect(check).toHaveClass('btn', 'h-9', 'py-0')
    expect(row.querySelector(':scope > span.subtle[aria-live="polite"]')).not.toBeNull()
    expect(input).toHaveAccessibleDescription('사업자등록번호는 저장 후 변경할 수 없습니다. 저장 전에 번호를 확인하세요.')
    const hint = document.getElementById(input.getAttribute('aria-describedby')!)!
    expect(row.contains(hint)).toBe(false)
    expect(hint.parentElement).toBe(row.parentElement)
    await paste('사업자등록번호', BRN)
    await user.click(check)
    const result = await screen.findByRole('status', { name: '중복 확인 결과' })
    expect(rowOf(input).contains(result)).toBe(false)
    // 결과 상자는 줄 아래 입력 열 정렬(.fieldoffset).
    expect(result.parentElement).toHaveClass('fieldoffset')
    expect(hint.compareDocumentPosition(result) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    // 형식 오류도 같은 줄 아래 설명(aria-describedby)으로.
    await user.clear(input)
    await paste('사업자등록번호', '123')
    await user.click(check)
    expect(input).toHaveAccessibleDescription('사업자등록번호는 숫자 10자리입니다.')
    expect(input).toHaveAttribute('aria-invalid', 'true')
  })

  it('userFeedback1-2: 업태/종목 rows = input filling the column + compact 삭제 on the same line (input height); 추가 is a small action under the rows; names, max count unchanged', async () => {
    renderWorkspace()
    for (const label of ['업태', '종목']) {
      const input = screen.getByLabelText(`${label} 1`)
      const remove = screen.getByRole('button', { name: `${label} 1 삭제` })
      expect(remove.parentElement!.contains(input)).toBe(true)
      expect(remove.parentElement).toHaveClass('fieldcontrol')
      expect(remove).toHaveTextContent(/^삭제$/)
      expect(remove).toHaveClass('btn', 'h-9', 'py-0')
      const add = screen.getByRole('button', { name: `${label} 추가` })
      expect(add).toHaveClass('btn', 'h-8', 'py-0')
      expect(add.closest('div')!.querySelector('input')).toBeNull()
      expect(add.parentElement).toHaveClass('fieldoffset')
      expect(remove.compareDocumentPosition(add) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    }
    for (let n = 2; n <= 10; n += 1) await user.click(screen.getByRole('button', { name: '업태 추가' }))
    expect(screen.getByRole('button', { name: '업태 10 삭제' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '업태 추가' })).toBeDisabled()
    await user.click(screen.getByRole('button', { name: '업태 10 삭제' }))
    expect(screen.queryByLabelText('업태 10')).toBeNull()
    expect(screen.getByRole('button', { name: '업태 추가' })).toBeEnabled()
  })

  it('userFeedback1-3 (사용자 결정 2026-11-19): every field is a .fieldrow — name on the left as a real <label for>, input (+ row buttons) on the right, help/error under the input column; names and descriptions unchanged', async () => {
    renderWorkspace()
    const labels = [
      '사업자등록번호', '상호', '개업일', '국가', '영문 상호(선택)', '업태 1', '종목 1', '사업장 우편번호', '사업장 기본주소', '사업장 상세주소(선택)',
      '대표자 1 이름', '대표자 1 유형', '사업체 전화', '사업체 이메일(선택)', '담당자 1 이름', '담당자 1 이메일', '담당자 1 전화',
    ]
    for (const label of labels) {
      const input = screen.getByLabelText(label)
      const row = rowOf(input)
      expect(row, label).not.toBeNull()
      const name = row.firstElementChild as HTMLLabelElement
      expect(name.tagName.toLowerCase()).toBe('label')
      expect(name.htmlFor).toBe(input.id)
      expect(name.textContent).toBe(label)
      expect(input.closest('.fieldcontrol')!.parentElement).toBe(row)
      // 상세 DetailRow와 같은 이름 글자·줄 사이 선(사용자 지시 2026-11-19).
      expect(name).toHaveClass('text-xs', 'text-muted-foreground')
      expect(row).toHaveClass('border-b', 'py-2', 'last:border-b-0')
      const describedBy = input.getAttribute('aria-describedby')
      if (describedBy) expect(document.getElementById(describedBy)).toHaveClass('fieldnote')
    }
    expect(screen.getByLabelText('국가')).toHaveAccessibleDescription('대한민국(KR)만 — 국외·비영리 확장은 정책 미정(TBD-10)')
    expect(screen.getByLabelText('사업체 전화')).toHaveAccessibleDescription('등록 완료 시 필수')
    // 사업체 구분: 같은 줄 모양의 radiogroup(이름 왼쪽 — aria-label 그대로), 법인일 때 법인등록번호 줄.
    const type = screen.getByRole('radiogroup', { name: '사업체 구분' })
    expect(type).toHaveClass('fieldrow')
    expect(type.firstElementChild).toHaveTextContent('사업체 구분')
    await user.click(screen.getByRole('radio', { name: '법인' }))
    expect(rowOf(screen.getByLabelText('법인등록번호')).firstElementChild).toHaveTextContent('법인등록번호')
    // 줄 밖 보조 요소(실사업장 체크·추가 버튼·대표자 안내)는 입력 열에 맞춘다.
    expect(screen.getByRole('checkbox', { name: '실사업장이 사업장과 다름' }).closest('label')).toHaveClass('fieldoffset')
    for (const name of ['대표자 추가', '담당자 추가']) expect(screen.getByRole('button', { name }).parentElement).toHaveClass('fieldoffset')
    expect(screen.getByText('단독 대표는 1명일 때만, 2명 이상이면 공동·각자를 고르세요.')).toHaveClass('fieldoffset')
    // 대표자·담당자 삭제도 업태와 같은 짧은 '삭제'(이름 그대로).
    expect(screen.getByRole('button', { name: '대표자 1 삭제' })).toHaveTextContent(/^삭제$/)
    expect(screen.getByRole('button', { name: '담당자 1 삭제' })).toHaveTextContent(/^삭제$/)
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('userFeedback1-4 (사용자 결정 2026-11-19): input widths follow the DB column length steps (xs ≤5 · sm ≤14 · md ≤50 · lg ≤100 · full >100)', async () => {
    renderWorkspace()
    await user.click(screen.getByRole('radio', { name: '법인' }))
    const width = (label: string) => {
      const control = screen.getByLabelText(label).closest('.fieldcontrol')!
      return Array.from(control.classList).find((c) => c.startsWith('fw-')) ?? 'full'
    }
    expect(Object.fromEntries([
      '사업자등록번호', '법인등록번호', '상호', '개업일', '국가', '영문 상호(선택)', '업태 1', '종목 1', '사업장 우편번호', '사업장 기본주소', '사업장 상세주소(선택)',
      '대표자 1 이름', '대표자 1 유형', '사업체 전화', '사업체 이메일(선택)', '담당자 1 이름', '담당자 1 이메일', '담당자 1 전화',
    ].map((label) => [label, width(label)]))).toEqual({
      사업자등록번호: 'fw-sm', 법인등록번호: 'fw-sm', 상호: 'fw-lg', 개업일: 'fw-sm', 국가: 'fw-xs', '영문 상호(선택)': 'fw-lg', '업태 1': 'fw-md', '종목 1': 'fw-md',
      '사업장 우편번호': 'fw-xs', '사업장 기본주소': 'full', '사업장 상세주소(선택)': 'full', '대표자 1 이름': 'fw-md', '대표자 1 유형': 'fw-sm',
      '사업체 전화': 'fw-sm', '사업체 이메일(선택)': 'full', '담당자 1 이름': 'fw-md', '담당자 1 이메일': 'full', '담당자 1 전화': 'fw-sm',
    })
  })

  it('userFeedback1 CSS: one form-row adaptation rule set (label column, help under the input column, width steps, ≤720px stacked + full width) — no colors', () => {
    const block = WORKSPACE_CSS.slice(WORKSPACE_CSS.indexOf('PayCore 적응 규칙'), WORKSPACE_CSS.indexOf('라이트 대비 보정'))
    // 사용자 지시(2026-11-19): 이름 열·간격은 사업자 조회 상세 DetailRow(sm:grid-cols-[140px_1fr] gap-1)와 같은 값.
    expect(block).toMatch(/^\.fieldrow\{display:grid;grid-template-columns:140px minmax\(0,1fr\);column-gap:4px;row-gap:4px;align-items:center\}$/m)
    expect(block).toMatch(/^\.fieldcontrol\{display:flex;flex-wrap:wrap;align-items:center;gap:8px;min-width:0\}$/m)
    // 리뷰 MINOR-1(기대값 변경 — 이번 피드백 새 시험): 입력 최소 16ch(12자리 번호가 다 보이게, 좁으면 버튼이 다음 줄). Input utility min-w-0을 넘으려고 !important, xs 단계는 0.
    expect(block).toMatch(/^\.fieldcontrol > :first-child\{flex:1 1 0%;min-width:min\(100%,16ch\) !important\}$/m)
    expect(block).toMatch(/^\.fieldcontrol\.fw-xs > :first-child\{min-width:0 !important\}$/m)
    expect(block).toMatch(/^\.fieldnote\{grid-column:2;margin:0\}$/m)
    expect(block).toMatch(/^\.fieldoffset\{padding:8px 0 8px calc\(140px \+ 4px\)\}$/m)
    expect(block).toMatch(/^\.fw-xs > :first-child\{max-width:10ch\}$/m)
    expect(block).toMatch(/^\.fw-sm > :first-child\{max-width:20ch\}$/m)
    expect(block).toMatch(/^\.fw-md > :first-child\{max-width:36ch\}$/m)
    expect(block).toMatch(/^\.fw-lg > :first-child\{max-width:60ch\}$/m)
    expect(block).toMatch(/@media\(max-width:720px\)\{\.fieldrow\{grid-template-columns:minmax\(0,1fr\)\}\.fieldnote\{grid-column:1\}\.fieldoffset\{padding-left:0\}\.fw-xs > :first-child,\.fw-sm > :first-child,\.fw-md > :first-child,\.fw-lg > :first-child\{max-width:none\}\}/)
    const rules = block.slice(block.indexOf('.fieldrow{'), block.indexOf('max-width:none}}') + 16)
    expect(rules).not.toMatch(/color|#[0-9a-f]{3,6}/i)
  })

  it('사용자 지시 2026-11-19: the form section card and row match the 사업자 조회 상세 InfoCard / DetailRow (same card classes, same 140px row look)', () => {
    const detail = render(<InfoCard title="1. 사업자 기본"><DetailRow term="상호">데모 사업자</DetailRow></InfoCard>)
    const card = detail.container.firstElementChild!
    const row = card.querySelector('dl > div')!
    const cardClass = card.className
    const rowClasses = ['border-b', 'py-2', 'last:border-b-0']
    detail.unmount()
    renderWorkspace()
    const section = screen.getByRole('region', { name: '사업자 기본' })
    expect(section.className).toBe(cardClass)
    expect(row).toHaveClass(...rowClasses, 'sm:grid-cols-[140px_1fr]')
    expect(rowOf(screen.getByLabelText('상호'))).toHaveClass(...rowClasses)
  })

  // LAYOUT-ROLLOUT B5 기대값 변경(사용자 결정 폼 줄): workspace 표시가 없어도 번호 줄·업태 줄은 폼 줄(중복 확인은 입력과 같은 .fieldcontrol, 짧은 '삭제' — 이름 그대로).
  // 버튼 모양(.btn 아님)만 workspace를 따른다 — 옛 단언 중 '.btn 아님'은 그대로 유지.
  it('userFeedback1 + B5: without the workspace flag the number row and 업태 rows are form rows too (중복 확인 beside the input, short 삭제 with the same name); buttons keep the non-workspace look', () => {
    render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'create' }} onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
    const check = screen.getByRole('button', { name: '중복 확인' })
    expect(check.parentElement!.contains(screen.getByLabelText('사업자등록번호'))).toBe(true)
    expect(check.parentElement).toHaveClass('fieldcontrol', 'fw-sm')
    expect(check).not.toHaveClass('btn')
    expect(screen.getByRole('button', { name: '업태 1 삭제' })).toHaveTextContent(/^삭제$/)
    expect(screen.getByRole('button', { name: '업태 1 삭제' }).parentElement).toBe(screen.getByLabelText('업태 1').closest('.fieldcontrol'))
    expect(screen.getByRole('button', { name: '업태 1 삭제' })).not.toHaveClass('btn')
  })

  // LAYOUT-ROLLOUT B5 기대값 변경(사용자 결정 폼 줄): 수정 폼(workspace 없음)도 옛 Card 6개 대신 등록 탭과 같은 번호 섹션 카드 6개. 껍데기(.mapnote·.cardtop 없음) 단언은 그대로.
  it('without the workspace flag (사업자 조회 상세 안의 수정 폼) the shell stays non-workspace but the six sections are the numbered section cards (B5)', () => {
    const { container } = render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'edit', detail: individualRegisteredDetail(1) }} onSaved={vi.fn()} onOpenBusiness={vi.fn()} />)
    expect(container.querySelector('.mapnote')).toBeNull()
    expect(container.querySelector('.cardtop')).toBeNull()
    expect(container.querySelectorAll('[data-slot="card"]')).toHaveLength(0)
    expect(screen.getAllByRole('region').map((r) => r.getAttribute('aria-label'))).toEqual(SECTIONS)
    for (const region of screen.getAllByRole('region')) expect(region).toHaveClass('rounded-xl', 'border', 'bg-card', 'px-4', 'py-3')
  })
})

/**
 * LAYOUT-ROLLOUT B5(사용자 결정 2026-11-19 폼 줄 — 모든 입력 화면): 사업자 조회 상세 안의 수정 폼(workspace 표시 없음)도 등록 탭과 같은 폼 줄·번호 섹션 카드.
 * 껍데기(.mapnote/.cardnote/.cardbody·'← 목록으로'·.btn)는 workspace만 — 수정 폼은 PageHeader + 기존 버튼 모양 그대로. 동작은 BusinessesScreen.test.tsx·BusinessProfileFormScreen.test.tsx가 지킨다.
 * 명시 Demo Fixture: individualRegisteredDetail(데모 사업자 · 778*******, REGISTERED v3).
 */
const EDIT_WIDTHS: Record<string, string> = {
  상호: 'fw-lg', 개업일: 'fw-sm', 국가: 'fw-xs', '영문 상호(선택)': 'fw-lg', '업태 1': 'fw-md', '종목 1': 'fw-md',
  '사업장 우편번호': 'fw-xs', '사업장 기본주소': 'full', '사업장 상세주소(선택)': 'full', '대표자 1 이름': 'fw-md', '대표자 1 유형': 'fw-sm',
  '사업체 전화': 'fw-sm', '사업체 이메일(선택)': 'full', '담당자 1 이름': 'fw-md', '담당자 1 이메일': 'full', '담당자 1 전화': 'fw-sm',
}
/** 입력 하나의 폼 줄: label[for] = 입력 id(이름 글자 그대로) · .fieldcontrol이 줄의 자식 · 폭 class(full = fw-* 없음). */
const expectFormRow = (label: string, width: string) => {
  const input = screen.getByLabelText(label)
  const row = rowOf(input)
  expect(row, label).not.toBeNull()
  const name = row.firstElementChild as HTMLLabelElement
  expect(name.tagName.toLowerCase()).toBe('label')
  expect(name.htmlFor).toBe(input.id)
  expect(name.textContent).toBe(label)
  expect(name).toHaveClass('text-xs', 'text-muted-foreground')
  expect(row).toHaveClass('border-b', 'py-2', 'last:border-b-0')
  const control = input.closest('.fieldcontrol') as HTMLElement
  expect(control.parentElement).toBe(row)
  const step = Array.from(control.classList).find((c) => c.startsWith('fw-')) ?? 'full'
  expect(step, label).toBe(width)
  const describedBy = input.getAttribute('aria-describedby')
  if (describedBy) expect(document.getElementById(describedBy)).toHaveClass('fieldnote')
  return { input, row, control }
}
/** 읽기 전용 사업자등록번호 줄(B2 ReadOnlyGroup과 같은 방식): group(이름 = aria-label) 자체가 .fieldrow — 이름 span aria-hidden 왼쪽 · 값 상자 .fieldcontrol(sm) · 안내 .fieldnote. */
const expectReadOnlyNumberRow = () => {
  const group = screen.getByRole('group', { name: '사업자등록번호' })
  expect(group).toHaveClass('fieldrow', 'border-b', 'py-2', 'last:border-b-0')
  const label = group.children[0] as HTMLElement
  expect(label.tagName.toLowerCase()).toBe('span')
  expect(label).toHaveAttribute('aria-hidden', 'true')
  expect(label.textContent).toBe('사업자등록번호')
  expect(label).toHaveClass('text-xs', 'text-muted-foreground')
  const control = group.children[1] as HTMLElement
  expect(control).toHaveClass('fieldcontrol', 'fw-sm')
  expect(control.firstElementChild).toHaveTextContent(/^778\*{7}$/)
  expect(control.firstElementChild).toHaveClass('border-dashed', 'font-mono')
  const note = group.children[2] as HTMLElement
  expect(note).toHaveClass('fieldnote')
  expect(note).toHaveTextContent('사업자등록번호 변경은 정책 미정으로 지원하지 않습니다.')
  // 수정 모드에는 번호 입력·중복 확인이 없다(읽기 전용 — 기존 동작).
  expect(within(group).queryByRole('textbox')).toBeNull()
  expect(screen.queryByRole('button', { name: '중복 확인' })).toBeNull()
  return group
}

describe('LAYOUT-ROLLOUT B5: 사업자 상세 안 수정 폼 = 폼 줄(이름 왼쪽 · 입력 오른쪽 · DB 열 길이 폭) + 번호 섹션 카드', { timeout: 20_000 }, () => {
  const renderEdit = (workspace = false) =>
    render(<BusinessProfileFormScreen api={fakeApi()} mode={{ kind: 'edit', detail: individualRegisteredDetail(1) }} workspace={workspace} onSaved={vi.fn()} onOpenBusiness={vi.fn()} onCancel={vi.fn()} />)

  it('수정 폼: 껍데기는 PageHeader 그대로(.mapnote/.cardnote/.cardbody 없음), 섹션은 등록 탭과 같은 번호 섹션 카드(lg 2열 grid) — 옛 Card 없음', () => {
    const { container } = renderEdit()
    expect(screen.getByRole('heading', { name: '사업자 수정' })).toBeInTheDocument()
    expect(container.querySelector('.mapnote')).toBeNull()
    expect(container.querySelector('.cardnote')).toBeNull()
    expect(container.querySelector('.cardbody')).toBeNull()
    expect(container.querySelector('.cardtop')).toBeNull()
    expect(container.querySelectorAll('[data-slot="card"]')).toHaveLength(0)
    const fieldset = container.querySelector('form > fieldset')!
    expect(shape(fieldset)).toEqual(['div.grid.gap-4.lg:grid-cols-2'])
    expect(shape(fieldset.firstElementChild!)).toEqual(Array(6).fill('section.flex.flex-col.gap-1.rounded-xl.border.bg-card.px-4.py-3'))
    SECTIONS.forEach((name, i) => {
      const section = screen.getByRole('region', { name })
      expect(shape(section)).toEqual(['h3.text-sm.font-semibold', 'div.flex.flex-col'])
      expect(section.querySelector(':scope > h3')).toHaveTextContent(`${i + 1}. ${name}`)
    })
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('수정 폼: 모든 입력이 .fieldrow(label[for]=id) · DB 열 길이 폭 · 사업자등록번호는 읽기 전용 줄(sm) + 변경 미지원 문구 · 사업체 구분 radiogroup 줄', () => {
    renderEdit()
    for (const [label, width] of Object.entries(EDIT_WIDTHS)) expectFormRow(label, width)
    expect(screen.getByLabelText('국가')).toHaveAccessibleDescription('대한민국(KR)만 — 국외·비영리 확장은 정책 미정(TBD-10)')
    expect(screen.getByLabelText('사업체 전화')).toHaveAccessibleDescription('등록 완료 시 필수')
    const group = expectReadOnlyNumberRow()
    expect(group.closest('section')).toBe(screen.getByRole('region', { name: '사업자 기본' }))
    const type = screen.getByRole('radiogroup', { name: '사업체 구분' })
    expect(type).toHaveClass('fieldrow')
    expect(type.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(type.firstElementChild).toHaveTextContent('사업체 구분')
    expect(screen.getByRole('checkbox', { name: '실사업장이 사업장과 다름' }).closest('label')).toHaveClass('fieldoffset')
    expect(screen.getByText('단독 대표는 1명일 때만, 2명 이상이면 공동·각자를 고르세요.')).toHaveClass('fieldoffset')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('수정 폼: 줄 끝 짧은 "삭제"(이름 그대로 · 입력과 같은 .fieldcontrol) · "추가"는 입력 열(.fieldoffset) — 버튼은 수정 폼 기존 모양(.btn 아님)', () => {
    renderEdit()
    for (const [label, remove] of [['업태 1', '업태 1 삭제'], ['종목 1', '종목 1 삭제'], ['대표자 1 이름', '대표자 1 삭제']] as const) {
      const button = screen.getByRole('button', { name: remove })
      expect(button).toHaveTextContent(/^삭제$/)
      expect(button.parentElement).toBe(screen.getByLabelText(label).closest('.fieldcontrol'))
      expect(button).not.toHaveClass('btn')
    }
    const contactRemove = screen.getByRole('button', { name: '담당자 1 삭제' })
    expect(contactRemove).toHaveTextContent(/^삭제$/)
    expect(contactRemove.parentElement).toHaveClass('fieldoffset')
    for (const name of ['업태 추가', '종목 추가', '대표자 추가', '담당자 추가']) {
      const add = screen.getByRole('button', { name })
      expect(add.parentElement).toHaveClass('fieldoffset')
      expect(add).not.toHaveClass('btn')
    }
    // 저장 줄(수정 폼 고유): 저장·취소 그대로, 기존 버튼 모양.
    expect(saveButtons().map((b) => b.textContent)).toEqual(['저장', '취소'])
    for (const b of saveButtons()) expect(b).not.toHaveClass('btn')
  })

  it('수정 폼: 중요 변경(법인으로) → 법인등록번호 줄(sm)·변경 사유 줄(full, 안내 = 설명) — 사유 칸은 섹션 밖 fieldset 안', async () => {
    renderEdit()
    expect(screen.queryByLabelText('변경 사유')).toBeNull()
    await user.click(screen.getByRole('radio', { name: '법인' }))
    expectFormRow('법인등록번호', 'fw-sm')
    const reason = expectFormRow('변경 사유', 'full')
    expect(reason.input).toHaveAccessibleDescription('중요정보(상호·사업체 구분·대표자·개업일·법인등록번호)를 바꾸려면 사유가 필요합니다. 개인정보를 적지 마세요.')
    expect(reason.row.closest('section[aria-label]')).toBeNull() // 섹션 카드(region) 밖 — 바깥 화면 section(PageHeader 껍데기)은 이름 없음
    expect(reason.row.closest('fieldset')).not.toBeNull()
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('reviewNIT(B5): workspace + 수정 모드도 사업자등록번호는 같은 읽기 전용 줄(sm) — 등록 탭 껍데기(.mapnote·.cardbody)는 그대로', () => {
    const { container } = renderEdit(true)
    expectReadOnlyNumberRow()
    expect(container.querySelector(':scope > .mapnote')).not.toBeNull()
    expect(container.querySelector('form > fieldset > div.cardbody')).not.toBeNull()
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })
})
