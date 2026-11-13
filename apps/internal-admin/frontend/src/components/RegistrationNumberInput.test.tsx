import { describe, expect, it } from 'vitest'
import { useState } from 'react'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { RegistrationNumberInput } from './RegistrationNumberInput'

/** 시험 입력 전용 번호(Demo — 실제 번호 아님). */
const BRN = '7781234560'

/** 상태는 숫자만 — 화면에는 하이픈. 마지막 상태값을 data 속성으로 보인다. */
function Harness() {
  const [digits, setDigits] = useState('')
  return (
    <>
      <label htmlFor="brn">사업자등록번호</label>
      <RegistrationNumberInput id="brn" value={digits} onValueChange={setDigits} />
      <output aria-label="보낼 값">{digits}</output>
    </>
  )
}

const user = userEvent.setup({ delay: null })
const field = () => screen.getByLabelText('사업자등록번호') as HTMLInputElement
const sent = () => screen.getByLabelText('보낼 값').textContent

describe('RegistrationNumberInput (ADMIN-UI-MERCHANT-002 — auto hyphen while typing; state stays digits only)', () => {
  it('typing shows 000-00-00000 while the value handed back is the 10 digits', async () => {
    render(<Harness />)
    await user.type(field(), BRN)
    expect(field()).toHaveValue('778-12-34560')
    expect(sent()).toBe(BRN)
    expect(field().selectionStart).toBe(12)
    expect(field()).toHaveAttribute('inputmode', 'numeric')
    expect(field()).toHaveAttribute('autocomplete', 'off')
    expect(field()).toHaveAttribute('type', 'text')
  })

  it('pasting a hyphenated or spaced number keeps digits only', async () => {
    render(<Harness />)
    await user.click(field())
    await user.paste('778 12-34560')
    expect(field()).toHaveValue('778-12-34560')
    expect(sent()).toBe(BRN)
  })

  it('backspace right after a hyphen deletes the digit before it (not a no-op on the hyphen)', async () => {
    render(<Harness />)
    await user.type(field(), BRN)
    // 커서를 "778-|12" (하이픈 뒤)에 둔다 → 하이픈 앞 '8'이 지워진다.
    field().setSelectionRange(4, 4)
    await user.keyboard('{Backspace}')
    expect(sent()).toBe('771234560')
    expect(field()).toHaveValue('771-23-4560')
    expect(field().selectionStart).toBe(2)
  })

  it('delete right before a hyphen deletes the digit after it', async () => {
    render(<Harness />)
    await user.type(field(), BRN)
    // "778|-12" → Delete → '1'이 지워진다.
    field().setSelectionRange(3, 3)
    await user.keyboard('{Delete}')
    expect(sent()).toBe('778234560')
    expect(field()).toHaveValue('778-23-4560')
    expect(field().selectionStart).toBe(3)
  })

  it('deleting a digit in the middle keeps the caret at the same digit position', async () => {
    render(<Harness />)
    await user.type(field(), BRN)
    // "778-12-3|4560" 에서 Backspace → 3 삭제, 커서는 남은 앞 숫자(5개) 바로 뒤.
    field().setSelectionRange(8, 8)
    await user.keyboard('{Backspace}')
    expect(sent()).toBe('778124560')
    expect(field()).toHaveValue('778-12-4560')
    expect(field().selectionStart).toBe(6)
  })

  it('an over-long paste is not cut down to a valid 10 digits', async () => {
    render(<Harness />)
    await user.click(field())
    await user.paste('778-12-345601')
    expect(sent()).toBe('77812345601')
    expect(field()).toHaveValue('778-12-345601')
  })
})
