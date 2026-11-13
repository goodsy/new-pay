import type { ComponentProps } from 'react'
import { cva, type VariantProps } from 'class-variance-authority'
import { cn } from '../../lib/utils'

/** shadcn/ui Badge(ADMIN-UI-001) + 표시용 success·warning tone(neutral 테마 확장). `asChild` 없음. */
export const badgeVariants = cva(
  'inline-flex w-fit shrink-0 items-center justify-center gap-1 overflow-hidden rounded-md border px-[7px] py-[5px] text-xs leading-none font-semibold whitespace-nowrap [&>svg]:pointer-events-none [&>svg]:size-3',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-chip-info-bg text-chip-info-fg',
        secondary: 'border-border bg-muted text-muted-foreground',
        destructive: 'border-destructive/30 bg-destructive/10 text-destructive',
        outline: 'text-foreground',
        success: 'border-transparent bg-chip-success-bg text-chip-success-fg',
        warning: 'border-transparent bg-chip-warning-bg text-chip-warning-fg',
      },
    },
    defaultVariants: { variant: 'default' },
  },
)

export type BadgeVariant = NonNullable<VariantProps<typeof badgeVariants>['variant']>

export function Badge({ className, variant, ...props }: ComponentProps<'span'> & VariantProps<typeof badgeVariants>) {
  return <span data-slot="badge" className={cn(badgeVariants({ variant }), className)} {...props} />
}
