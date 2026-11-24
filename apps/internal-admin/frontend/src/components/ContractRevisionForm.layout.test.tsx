import { describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ContractRevisionForm } from './ContractRevisionForm'
import { RegistrationStep } from './RegistrationStep'
import { createScreenMemory } from '../lib/screenMemory'
import { fakeApi } from '../test/fakeApi'

/**
 * LAYOUT-ROLLOUT B5(사용자 결정 2026-11-19 폼 줄 — 모든 입력 화면): 가맹점 조회 상세의 계약 Revision 등록 폼 입력도 이름 왼쪽(140px)·입력 오른쪽 같은 줄,
 * 폭은 DB 열 길이 단계(시작일·종료일 DATE → sm). 바깥 Card(비workspace RegistrationStep 껍데기)는 그대로, 안쪽 fieldset만 세로 줄([rows]).
 * 모양만 — 업무 동작(범위·검사·제출·결과 불명)은 MerchantsScreen.revision.test.tsx가 지킨다. Demo 식별자(업무 의미 없는 고정값).
 */
const id = (prefix: string, n: number) => `${prefix}_${String(n).padStart(26, '0')}`
const CTR1 = id('ctr', 1)
const MER1 = id('mer', 1)
const SITE1 = id('site', 1)
const user = userEvent.setup({ delay: null })

// 기대값 변경(MERCHANT-IA-001 B3 — 사용자 결정 D-ADMIN-UI-010 ③ "내부 식별자는 사람용 값으로"): 제목·Site 후보는 계약 키·Site 키 대신 사람용 이름(호출자가 준다 — 모양 단언은 그대로).
function renderForm() {
  render(
    <ContractRevisionForm api={fakeApi()} memory={createScreenMemory()} contractId={CTR1} contractLabel="계약 1" merchantId={MER1} siteIds={[SITE1]} siteLabel={() => '웹 · shop.example.test'} onRegistered={vi.fn()}
      newSubmissionId={() => '0'.repeat(32)} todayKst={() => '2026-11-10'} />,
  )
  return screen.getByRole('region', { name: '새 Revision 등록 — 계약 1' })
}
/** 입력 하나의 폼 줄: .fieldrow 안 label[for] = 입력 id(이름 글자 그대로), 입력은 .fieldcontrol(줄의 자식) 첫 칸, 폭 class. */
const formRowOf = (scope: HTMLElement, label: string, width: 'xs' | 'sm' | 'md' | 'lg' | null) => {
  const input = within(scope).getByLabelText(label)
  const row = input.closest('.fieldrow') as HTMLElement
  expect(row, label).not.toBeNull()
  const name = row.querySelector(':scope > label')!
  expect(name.textContent).toBe(label)
  expect(input.id).not.toBe('')
  expect(name.getAttribute('for')).toBe(input.id)
  const control = input.closest('.fieldcontrol') as HTMLElement
  expect(control.parentElement).toBe(row)
  expect(control.firstElementChild).toBe(input)
  expect(Array.from(control.classList).filter((c) => c.startsWith('fw-')), label).toEqual(width ? [`fw-${width}`] : [])
  return { input, row, control }
}

describe('LAYOUT-ROLLOUT B5: 계약 Revision 등록 폼 = 폼 줄(바깥 Card 그대로)', () => {
  it('바깥 Card 껍데기 그대로(.cardtop 없음), 안쪽 fieldset은 세로 줄(grid class 없음)', () => {
    const form = renderForm()
    expect(form).toHaveAttribute('data-slot', 'card')
    expect(form.querySelector('.cardtop')).toBeNull()
    const fieldset = form.querySelector('form > fieldset')!
    expect(fieldset.className).not.toMatch(/grid/)
    expect(fieldset).toHaveClass('m-0', 'min-w-0', 'border-0', 'p-0')
    expect(within(form).getByRole('button', { name: 'Revision 등록' })).not.toHaveClass('btn')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('범위 = 줄 모양 fieldset(legend sr-only 이름 그대로 · 보이는 이름 aria-hidden 왼쪽 · 선택지·Site 체크·오류는 오른쪽 열)', async () => {
    const form = renderForm()
    const scope = within(form).getByRole('group', { name: '범위' })
    expect(scope.tagName.toLowerCase()).toBe('fieldset')
    expect(scope.querySelector(':scope > legend')).toHaveClass('sr-only')
    const scopeRow = scope.querySelector(':scope > .fieldrow')!
    expect(scopeRow).toHaveClass('border-b', 'py-2')
    expect(scopeRow.firstElementChild).toHaveAttribute('aria-hidden', 'true')
    expect(scopeRow.firstElementChild!.textContent).toBe('범위')
    expect(scopeRow.firstElementChild).toHaveClass('text-xs', 'text-muted-foreground')
    const options = scopeRow.children[1] as HTMLElement
    expect(within(options).getAllByRole('radio')).toHaveLength(2)
    await user.click(within(scope).getByRole('radio', { name: '선택 Site' }))
    expect(options.contains(within(scope).getByRole('checkbox', { name: '웹 · shop.example.test' }))).toBe(true)
    // Site를 고르지 않고 제출 → 오류는 입력 열 아래(.fieldnote, 줄의 자식).
    await user.type(within(form).getByLabelText('시작일'), '2026-11-12')
    await user.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    const error = within(scope).getByText('Site를 하나 이상 고르세요.')
    expect(error).toHaveClass('fieldnote', 'text-destructive')
    expect(error.parentElement).toBe(scopeRow)
  })

  it('시작일 sm(도움말·오류 = 설명 .fieldnote) · 종료일 sm + 옆 문구 trailing(설명 연결 그 문구 하나 그대로) · 선택 안내/오류는 입력 열 아래', async () => {
    const form = renderForm()
    const start = formRowOf(form, '시작일', 'sm')
    expect(start.input).toHaveAttribute('min', '2026-11-10')
    expect(start.input).toHaveAccessibleDescription('오늘(한국 시간) 이후 날짜. 그 날 0시부터 효력 — 오늘보다 앞이면 서버가 거절합니다.')
    expect(document.getElementById(start.input.getAttribute('aria-describedby')!)).toHaveClass('fieldnote')

    const end = formRowOf(form, '종료일', 'sm')
    const endNote = document.getElementById(end.input.getAttribute('aria-describedby')!)!
    expect(endNote.parentElement).toBe(end.control)
    expect(endNote).toHaveTextContent('이 날 0시 종료(미포함)')
    expect(end.input).toHaveAccessibleDescription('이 날 0시 종료(미포함)')
    const optional = within(end.row).getByText('선택 — 비우면 종료 없음.')
    expect(optional).toHaveClass('fieldnote')
    expect(optional.parentElement).toBe(end.row)

    await user.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    const startError = within(start.row).getByText('시작일을 고르세요.')
    expect(startError).toHaveClass('fieldnote', 'text-destructive')
    expect(start.input).toHaveAccessibleDescription('시작일을 고르세요.')
    await user.type(start.input, '2026-11-12')
    await user.type(end.input, '2026-11-11')
    await user.click(within(form).getByRole('button', { name: 'Revision 등록' }))
    const endError = within(end.row).getByText('종료일은 시작일보다 뒤여야 합니다.')
    expect(endError).toHaveClass('fieldnote', 'text-destructive')
    expect(within(end.row).queryByText('선택 — 비우면 종료 없음.')).toBeNull()
    expect(end.input).toHaveAccessibleDescription('이 날 0시 종료(미포함)')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
  })

  it('RegistrationStep [rows]는 fieldset만 세로 줄로 — 껍데기(Card)·버튼 모양은 workspace 값과 따로', () => {
    const { container } = render(
      <RegistrationStep memory={createScreenMemory()} memoryKey="x" api={{ send: vi.fn() }} newSubmissionId={() => 'a'.repeat(32)} collect={() => null} onSucceeded={vi.fn()} title="계약 Revision" submitLabel="등록" rows>
        {() => null}
      </RegistrationStep>,
    )
    expect(container.querySelector('[data-slot="card"]')).not.toBeNull()
    expect(container.querySelector('.cardtop')).toBeNull()
    expect(container.querySelector('form > fieldset')!.className).not.toMatch(/grid/)
    expect(screen.getByRole('button', { name: '등록' })).not.toHaveClass('btn')
  })
})

/** LAYOUT-ROLLOUT B6(B5 390px 캡처 후속 — 모양만): 카드 제목의 계약 ID가 넘치면 아무 곳에서나 줄을 바꿔 잘리지 않는다(넘치지 않으면 그대로). */
describe('LAYOUT-ROLLOUT B6: 계약 Revision 등록 카드 제목 넘침 방지', () => {
  it('CardTitle = wrap-anywhere(접근성 이름 · 글자 그대로)', () => {
    const form = renderForm()
    const title = form.querySelector('[data-slot="card-title"]')!
    expect(title).toHaveTextContent('새 Revision 등록 — 계약 1')
    expect(title).toHaveClass('wrap-anywhere')
  })
})
