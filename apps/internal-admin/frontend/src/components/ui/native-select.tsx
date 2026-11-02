import type { ComponentProps } from 'react'
import { ChevronDownIcon } from '../icons'
import { cn } from '../../lib/utils'

/**
 * shadcn/ui Native Select(ADMIN-UI-001): 브라우저 `<select>`에 shadcn 모양만 입힌다. Radix Select는 열 때 react-remove-scroll이 `<style>`을 주입해
 * 관리자 CSP(style-src 'self')를 어기므로 쓰지 않는다.
 */
export function NativeSelect({ className, ...props }: ComponentProps<'select'>) {
  return (
    <span data-slot="native-select-wrapper" className="relative flex w-full min-w-0 items-center">
      <select
        data-slot="native-select"
        className={cn(
          'h-9 w-full min-w-0 appearance-none rounded-md border border-input bg-transparent py-1 pr-9 pl-3 text-sm font-normal shadow-xs transition-[color,box-shadow] outline-none disabled:cursor-not-allowed disabled:opacity-50 dark:bg-input/30 [&>option]:bg-popover [&>option]:text-popover-foreground',
          'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50',
          className,
        )}
        {...props}
      />
      <ChevronDownIcon aria-hidden="true" className="pointer-events-none absolute right-3 size-4 text-muted-foreground" />
    </span>
  )
}
