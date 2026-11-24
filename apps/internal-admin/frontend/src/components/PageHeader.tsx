import { memo, type ReactNode, type Ref } from 'react'

/**
 * 화면 머리(ADMIN-UI-001): 제목(h2 — 기존 heading 이름 그대로) + 짧은 설명.
 * rev 0.4: 화면은 메뉴 카드(탭) 안에 놓이므로 참고 시안의 카드 제목 크기로 그린다 — 쪽 제목(h1)은 [WorkspaceHeader].
 * LAYOUT-ROLLOUT B1(D-ADMIN-UI-006): [action]이 있으면 제목 줄 오른쪽에 둔다(사업자 목록의 '＋ 사업자 등록'). 없으면 지금 모양 그대로.
 * reviewFix1 MINOR-3: [focusable]이면 제목 h2에 tabIndex -1 — 버튼으로 이 화면에 왔을 때 App이 초점을 옮길 자리(Tab 순서에는 없음). 없으면 지금 그대로.
 */
export const PageHeader = memo(function PageHeader({ title, description, action, focusable = false, titleRef }: {
  title: ReactNode; description: string; action?: ReactNode; focusable?: boolean
  /** MERCHANT-DETAIL-VIEW-001 리뷰 MINOR-2: 화면 안 버튼 전환(상세 → 목록) 뒤 이 제목으로 초점을 옮길 때([focusable]과 함께). */
  titleRef?: Ref<HTMLHeadingElement>
}) {
  const text = (
    <div className="flex flex-col gap-1">
      <h2 className="text-[17px] font-bold text-heading" tabIndex={focusable ? -1 : undefined} ref={titleRef}>{title}</h2>
      <p className="text-xs text-muted-foreground">{description}</p>
    </div>
  )
  if (!action) return text
  return (
    <div className="flex flex-wrap items-start justify-between gap-3">
      {text}
      {action}
    </div>
  )
})
