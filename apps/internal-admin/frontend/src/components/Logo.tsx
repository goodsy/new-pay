import { cn } from '../lib/utils'

/**
 * 브랜드 로고(장식 — 스크린리더에서 숨김).
 * [plain] — 참고 시안 `.brand i` 자리(ADMIN-UI-WORKSPACE-001): 모양은 workspace.css의 `.brand i`가 정한다(33px 파란 사각형).
 * 그 밖(로그인 카드 등)은 기존 사각형(색은 --brand, styles.css).
 */
export function Logo({ className, plain = false }: { className?: string; plain?: boolean }) {
  if (plain) return <i aria-hidden="true">P</i>
  return (
    <span aria-hidden="true" className={cn('flex size-8 shrink-0 items-center justify-center rounded-[11px] bg-primary text-lg font-extrabold text-primary-foreground', className)}>
      P
    </span>
  )
}
