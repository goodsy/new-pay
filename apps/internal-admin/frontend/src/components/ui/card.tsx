import type { ComponentProps } from 'react'
import { cn } from '../../lib/utils'

/** shadcn/ui Card(ADMIN-UI-001; rev 0.4 참고 시안 `.card` — 13px 모서리, 그림자 없이 옅은 선). `as="section"`이면 기존 화면의 section 요소를 그대로 둔다(role region 이름·구조 유지). */
export function Card({ className, as: Element = 'div', ...props }: ComponentProps<'div'> & { as?: 'div' | 'section' }) {
  return <Element data-slot="card" className={cn('flex flex-col gap-6 rounded-[13px] border bg-card py-6 text-card-foreground', className)} {...props} />
}

export function CardHeader({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-header" className={cn('grid auto-rows-min items-start gap-1.5 px-6', className)} {...props} />
}

export function CardTitle({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-title" className={cn('leading-none font-semibold', className)} {...props} />
}

export function CardDescription({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-description" className={cn('text-sm text-muted-foreground', className)} {...props} />
}

export function CardContent({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-content" className={cn('px-6', className)} {...props} />
}

export function CardFooter({ className, ...props }: ComponentProps<'div'>) {
  return <div data-slot="card-footer" className={cn('flex items-center px-6', className)} {...props} />
}
