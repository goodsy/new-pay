import type { ComponentProps } from 'react'
import { cn } from '../../lib/utils'

/**
 * shadcn/ui Label(ADMIN-UI-001) — Radix Label 대신 native `<label>`. 화면은 입력 요소를 label 안에 넣어(문구는 `<span>`) 접근성 이름을 기존과 같게 둔다.
 */
export function Label({ className, ...props }: ComponentProps<'label'>) {
  return (
    <label
      data-slot="label"
      className={cn('flex flex-col gap-2 text-sm leading-none font-medium select-none has-[:disabled]:opacity-50', className)}
      {...props}
    />
  )
}
