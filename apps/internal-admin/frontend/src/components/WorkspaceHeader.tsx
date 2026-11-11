import type { ReactNode } from 'react'

/**
 * 쪽 머리(rev 0.4, 참고 시안 `.heading`): 작은 영문 eyebrow, 큰 제목(h1), 한 줄 설명.
 * [action] — 오른쪽 주요 버튼 자리(ADMIN-UI-MERCHANT-001: 참고 시안 '＋ 사업자 등록'). 탭 안 양식과 중복되는 버튼은 두지 않는다 — 탭이 아닌 화면을 여는 버튼만.
 */
export function WorkspaceHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-end justify-between gap-4">
      <div className="flex min-w-0 flex-col">
        <p aria-hidden="true" className="mb-3 text-[10px] tracking-[1.6px] text-eyebrow uppercase">{eyebrow}</p>
        <h1 className="mb-2.5 text-[28px] leading-tight font-bold tracking-[-1px]">{title}</h1>
        <p className="text-xs text-muted-foreground">{description}</p>
      </div>
      {action && <div className="flex shrink-0 gap-2">{action}</div>}
    </div>
  )
}
