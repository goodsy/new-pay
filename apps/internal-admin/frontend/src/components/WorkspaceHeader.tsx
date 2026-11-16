import type { ReactNode } from 'react'

/**
 * 쪽 머리(ADMIN-UI-WORKSPACE-001, 참고 시안 app.js head() 그대로): `.heading` > (div > `.eyebrow`·h1·p) + `.actions`(비어 있어도 둔다).
 * [action] — 오른쪽 주요 버튼 자리(ADMIN-UI-MERCHANT-001). 탭 안 양식과 중복되는 버튼은 두지 않는다 — 탭이 아닌 화면을 여는 버튼만.
 */
export function WorkspaceHeader({ eyebrow, title, description, action }: { eyebrow: string; title: string; description: string; action?: ReactNode }) {
  return (
    <div className="heading">
      <div>
        <div className="eyebrow" aria-hidden="true">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="actions">{action}</div>
    </div>
  )
}
