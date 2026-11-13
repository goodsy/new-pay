import { useLayoutEffect, useRef, type ChangeEvent, type ComponentProps, type KeyboardEvent } from 'react'
import { caretAfterDigits, digitsBefore, digitsOf, formatRegistrationNumber } from '../lib/registrationNumber'
import { cn } from '../lib/utils'
import { Input } from './ui/input'

type Props = Omit<ComponentProps<'input'>, 'value' | 'onChange' | 'type' | 'inputMode' | 'maxLength'> & {
  /** 숫자만(화면 상태·서버 값 — 하이픈 없음). */
  value: string
  /** 숫자만 돌려준다. */
  onValueChange: (digits: string) => void
}

/**
 * 사업자등록번호 입력란(ADMIN-UI-MERCHANT-002): 입력 중 `000-00-00000`으로 하이픈을 자동으로 보이고, 호출자에게는 **숫자만** 돌려준다.
 * - 붙여넣기: 하이픈·공백을 버리고 숫자만. 11자리 이상은 잘라 맞추지 않는다(형식 오류 — [digitsOf]).
 * - 커서: 바뀐 뒤에도 같은 숫자 뒤에 둔다. 하이픈 바로 뒤 Backspace는 하이픈 앞 숫자를, 하이픈 바로 앞 Delete는 하이픈 뒤 숫자를 지운다.
 * - maxLength를 두지 않는다 — 브라우저가 붙여넣은 글자를 잘라 10자리로 맞추는 일을 막는다.
 * - autoComplete off·type text(비밀번호 칸 아님 — 기존과 같다). 값은 메모리 상태에만(저장소 없음 — 호출자 규칙 그대로).
 */
export function RegistrationNumberInput({ value, onValueChange, onKeyDown, autoComplete = 'off', className, ...rest }: Props) {
  const ref = useRef<HTMLInputElement>(null)
  /** 다음 렌더 뒤 커서를 둘 "앞 숫자 수"(없으면 건드리지 않는다). */
  const caret = useRef<number | null>(null)
  const shown = formatRegistrationNumber(value)

  useLayoutEffect(() => {
    const input = ref.current
    if (caret.current === null || input === null) return
    const position = caretAfterDigits(shown, caret.current)
    caret.current = null
    if (input.ownerDocument.activeElement === input) input.setSelectionRange(position, position)
  }, [shown])

  const change = (e: ChangeEvent<HTMLInputElement>) => {
    const raw = e.target.value
    const digits = digitsOf(raw)
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

  return (
    <Input
      {...rest}
      ref={ref}
      type="text"
      inputMode="numeric"
      autoComplete={autoComplete}
      value={shown}
      onChange={change}
      onKeyDown={keyDown}
      placeholder={rest.placeholder ?? '000-00-00000'}
      className={cn('font-mono', className)}
    />
  )
}
