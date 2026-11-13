// @vitest-environment node
import { describe, expect, it } from 'vitest'
import { caretAfterDigits, digitsOf, formatRegistrationNumber, isRegistrationNumber } from './registrationNumber'

/** ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004): 하이픈은 화면 표시에만 — 상태·서버 값은 숫자 10자리 그대로. */
describe('registrationNumber (display-only hyphens 000-00-00000)', () => {
  it('formats progressively: the hyphen appears only when the next group starts', () => {
    expect(formatRegistrationNumber('')).toBe('')
    expect(formatRegistrationNumber('778')).toBe('778')
    expect(formatRegistrationNumber('7781')).toBe('778-1')
    expect(formatRegistrationNumber('77812')).toBe('778-12')
    expect(formatRegistrationNumber('778123')).toBe('778-12-3')
    expect(formatRegistrationNumber('7781234560')).toBe('778-12-34560')
  })

  it('keeps ASCII digits only and never truncates an over-long input down to a valid 10 digits', () => {
    expect(digitsOf('778-12-34560')).toBe('7781234560')
    expect(digitsOf(' 778 12 34560 ')).toBe('7781234560')
    // 전각 숫자·문자는 숫자로 바꾸지 않는다(버린다).
    expect(digitsOf('７78ab1')).toBe('781')
    // 11자리 이상은 11자리로 남겨 형식 오류가 되게 한다 — 붙여넣은 잘못된 번호가 10자리로 잘려 맞는 번호처럼 보내지지 않게.
    expect(digitsOf('778123456012345')).toBe('77812345601')
    expect(isRegistrationNumber(digitsOf('778123456012345'))).toBe(false)
    expect(formatRegistrationNumber('77812345601')).toBe('778-12-345601')
  })

  it('validates exactly 10 ASCII digits (the server format, unchanged)', () => {
    expect(isRegistrationNumber('7781234560')).toBe(true)
    expect(isRegistrationNumber('778-12-34560')).toBe(false)
    expect(isRegistrationNumber('778123456')).toBe(false)
  })

  it('caretAfterDigits places the caret right after the n-th digit of the formatted text', () => {
    expect(caretAfterDigits('778-12-34560', 0)).toBe(0)
    expect(caretAfterDigits('778-12-34560', 3)).toBe(3)
    expect(caretAfterDigits('778-12-34560', 4)).toBe(5)
    expect(caretAfterDigits('778-12-34560', 5)).toBe(6)
    expect(caretAfterDigits('778-12-34560', 6)).toBe(8)
    expect(caretAfterDigits('778-12-34560', 10)).toBe(12)
  })
})
