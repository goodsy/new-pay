import { memo, type ReactNode } from 'react'

/**
 * 화면 머리(ADMIN-UI-001): 제목(h2 — 기존 heading 이름 그대로) + 짧은 설명.
 * rev 0.4: 화면은 메뉴 카드(탭) 안에 놓이므로 참고 시안의 카드 제목 크기로 그린다 — 쪽 제목(h1)은 [WorkspaceHeader].
 */
export const PageHeader = memo(function PageHeader({ title, description }: { title: ReactNode; description: string }) {
  return (
    <div className="flex flex-col gap-1">
      <h2 className="text-[17px] font-bold text-heading">{title}</h2>
      <p className="text-xs text-muted-foreground">{description}</p>
    </div>
  )
})
