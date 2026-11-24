import type { ReactNode, Ref } from 'react'

/**
 * 탭 패널 제목 줄(참고 시안 `.mapnote` — `<b>탭 이름</b> · 설명`, 화면 이름은 h2). MenuCard flush 화면이 쓴다.
 * LAYOUT-ROLLOUT B1(사용자 결정 D-ADMIN-UI-006): [action]이 있으면 글은 왼쪽 묶음(div), 버튼은 줄 오른쪽 — 목록의 '＋ … 등록'·등록 화면의 '← 목록으로'.
 * 배치는 workspace.css `.mapnote:has(> .btn)`(새 색·크기 없음). [action]이 없으면 지금 모양 그대로(h2가 .mapnote 바로 안).
 * reviewFix1 MINOR-3: h2는 tabIndex -1 — 버튼으로 화면이 바뀌면 App이 이 제목으로 초점을 옮긴다(Tab 순서에는 없음, 초점 링은 workspace.css).
 */
export function MapNote({ title, children, action, titleRef }: { title: ReactNode; children: ReactNode; action?: ReactNode; titleRef?: Ref<HTMLHeadingElement> }) {
  const text = (
    <>
      {/* MERCHANT-DETAIL-VIEW-001 리뷰 MINOR-2: [titleRef] — 화면 안 버튼 전환(목록 ↔ 상세) 뒤 화면이 이 제목으로 초점을 옮길 때 쓴다(App 전환과 같은 h2 tabIndex -1). */}
      <h2 tabIndex={-1} ref={titleRef}>{title}</h2>
      {' · '}
      {children}
    </>
  )
  return <div className="mapnote">{action ? <><div>{text}</div>{action}</> : text}</div>
}

/** 등록 화면 제목 줄의 '← 목록으로'(reviewFix1 NIT: 화살표는 장식 — 접근 가능한 이름은 '목록으로'). */
export function BackToListButton({ onClick }: { onClick: () => void }) {
  return (
    <button type="button" className="btn" onClick={onClick}>
      <span aria-hidden="true">←</span> 목록으로
    </button>
  )
}
