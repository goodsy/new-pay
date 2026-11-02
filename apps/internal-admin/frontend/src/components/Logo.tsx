import { cn } from '../lib/utils'

/** 브랜드 로고 사각형(장식 — 스크린리더에서 숨김). 색은 --brand(styles.css), 모양은 참고 시안 brand 블록(rev 0.4). */
export function Logo({ className }: { className?: string }) {
  return (
    <span aria-hidden="true" className={cn('flex size-8 shrink-0 items-center justify-center rounded-[11px] bg-primary text-lg font-extrabold text-primary-foreground', className)}>
      P
    </span>
  )
}
