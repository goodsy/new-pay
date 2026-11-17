import { useLayoutEffect, useRef, type ChangeEvent, type ComponentProps, type KeyboardEvent } from 'react'
import { caretAfterDigits, digitsBefore } from '../lib/registrationNumber'
import { cn } from '../lib/utils'
import { Input } from './ui/input'

type Props = Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type' | 'inputMode' | 'maxLength'> & {
  /** 숫자만(화면 상태·서버 값 — 하이픈 없음). */
  value: string
  onValueChange: (digits: string) => void
  /** 숫자 → 표시(하이픈 — 표시만). */
  format: (digits: string) => string
  /** 받는 숫자 최대 개수. 넘는 숫자는 한 자리만 더 남겨 형식 오류로 보이게 한다(잘라 맞추지 않음 — RegistrationNumberInput과 같은 규칙). */
  maxDigits: number
}

/**
 * 숫자 입력란(BUSINESS-PROFILE-001 §8.3 — 전화·법인등록번호): 입력 중 [format]으로 하이픈을 보이고 호출자에게는 **숫자만** 돌려준다(보내는 값은 숫자만 — X-14 서버 정규화 없음).
 * 커서·하이픈 앞뒤 지우기는 [RegistrationNumberInput]과 같다.
 */
export function DigitsInput({ value, onValueChange, format, maxDigits, onKeyDown, autoComplete = 'off', className, ...rest }: Props) {
  const ref = useRef<HTMLInputElement>(null)
  const caret = useRef<number | null>(null)
  const shown = format(value)

  useLayoutEffect(() => {
    const input = ref.current
    if (caret.current === null || input === null) return
    const position = caretAfterDigits(shown, caret.current)
    caret.current = null
    if (input.ownerDocument.activeElement === input) input.setSelectionRange(position, position)
  }, [shown])

  const change = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value
    const digits = raw.replace(/[^0-9]/g, '').slice(0, maxDigits + 1)
    caret.current = Math.min(digitsBefore(raw, e.target.selectionStart ?? raw.length), digits.length)
    onValueChange(digits)
  }

  const keyDown = (e: KeyboardEvent<HTMLInputElement>) => {
    onKeyDown?.(e)
    if (e.defaultPrevented) return
    const input = e.currentTarget
    const start = input.selectionStart
    if (start === null || start !== input.selectionEnd) return
    if (e.key === 'Backspace' && start > 0 && shown[start - 1] === '-') {
      e.preventDefault()
      const before = digitsBefore(shown, start)
      if (before === 0) return
      caret.current = before - 1
      onValueChange(value.slice(0, before - 1) + value.slice(before))
    } else if (e.key === 'Delete' && shown[start] === '-') {
      e.preventDefault()
      const before = digitsBefore(shown, start)
      caret.current = before
      onValueChange(value.slice(0, before) + value.slice(before + 1))
    }
  }

  return <Input {...rest} ref={ref} type="text" inputMode="numeric" autoComplete={autoComplete} value={shown} onChange={change} onKeyDown={keyDown} className={cn('font-mono', className)} />
}
