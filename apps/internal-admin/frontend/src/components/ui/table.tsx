import type { ComponentProps } from 'react'
import { cn } from '../../lib/utils'

/** shadcn/ui Table(ADMIN-UI-001). 바깥 div는 가로 스크롤만 맡고 aria-label 등 속성은 그대로 table에 둔다. */
export function Table({ className, ...props }: ComponentProps<'table'>) {
  return (
    <div data-slot="table-container" className="relative w-full overflow-x-auto rounded-xl border bg-card">
      <table data-slot="table" className={cn('w-full caption-bottom text-sm', className)} {...props} />
    </div>
  )
}

export function TableHeader({ className, ...props }: ComponentProps<'thead'>) {
  return <thead data-slot="table-header" className={cn("bg-muted/50 [&_tr]:border-b", className)} {...props} />
}

export function TableBody({ className, ...props }: ComponentProps<'tbody'>) {
  return <tbody data-slot="table-body" className={cn('[&_tr:last-child]:border-0', className)} {...props} />
}

export function TableRow({ className, ...props }: ComponentProps<'tr'>) {
  return <tr data-slot="table-row" className={cn('border-b transition-colors hover:bg-muted/50', className)} {...props} />
}

export function TableHead({ className, ...props }: ComponentProps<'th'>) {
  return (
    <th
      data-slot="table-head"
      className={cn('bg-table-head px-5 py-[13px] text-left align-middle text-[13px] font-semibold whitespace-nowrap text-subtle', className)}
      {...props}
    />
  )
}

export function TableCell({ className, ...props }: ComponentProps<'td'>) {
  return <td data-slot="table-cell" className={cn('px-5 py-[17px] align-middle', className)} {...props} />
}
