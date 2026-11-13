import type { ComponentProps } from 'react'
import { cn } from '../../lib/utils'

/** shadcn/ui Input(ADMIN-UI-001). */
export function Input({ className, type, ...props }: ComponentProps<'input'>) {
  return (
    <input
      type={type}
      data-slot="input"
      className={cn(
        'h-9 w-full min-w-0 rounded-md border border-input bg-transparent px-3 py-1 text-base font-normal shadow-xs transition-[color,box-shadow] outline-none placeholder:text-muted-foreground selection:bg-primary selection:text-primary-foreground disabled:pointer-events-none disabled:cursor-not-allowed disabled:opacity-50 md:text-[13px] dark:bg-input/30',
        'focus-visible:border-ring focus-visible:ring-[3px] focus-visible:ring-ring/50',
        'aria-invalid:border-destructive aria-invalid:ring-destructive/20',
        className,
      )}
      {...props}
    />
  )
}
