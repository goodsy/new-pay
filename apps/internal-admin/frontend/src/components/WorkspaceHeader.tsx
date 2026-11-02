/** 쪽 머리(rev 0.4, 참고 시안 `.heading`): 작은 영문 eyebrow, 큰 제목(h1), 한 줄 설명. 기존 화면의 동작은 각 탭 안 양식에 그대로 둔다(머리에 중복 버튼 없음). */
export function WorkspaceHeader({ eyebrow, title, description }: { eyebrow: string; title: string; description: string }) {
  return (
    <div className="flex min-w-0 flex-col">
      <p aria-hidden="true" className="mb-3 text-[10px] tracking-[1.6px] text-eyebrow uppercase">{eyebrow}</p>
      <h1 className="mb-2.5 text-[28px] leading-tight font-bold tracking-[-1px]">{title}</h1>
      <p className="text-xs text-muted-foreground">{description}</p>
    </div>
  )
}
