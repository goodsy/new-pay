import type { ReactNode } from 'react'

/**
 * 상세 정보 카드(사업자 조회 상세 DetailInfo에서 그대로 옮김 — 출력 동일, ADMIN-UI-WORKSPACE-001 사용자 지시 2026-11-19).
 * 번호 붙은 섹션 카드 + 정의 목록. 등록·수정 폼의 [FormRow](components/FormRow.tsx)와 같은 문서 모양(이름 열 140px · 줄 사이 얇은 선)으로 맞춘다.
 */
export function InfoCard({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="flex flex-col gap-1 rounded-xl border bg-card px-4 py-3">
      <h4 className="text-sm font-semibold">{title}</h4>
      <dl>{children}</dl>
    </div>
  )
}

/** 상세 한 줄: 이름(dt — 140px 열) + 값(dd). 640px 미만은 위아래. */
export function DetailRow({ term, children }: { term: string; children: ReactNode }) {
  return (
    <div className="grid gap-1 border-b py-2 last:border-b-0 sm:grid-cols-[140px_1fr]">
      <dt className="text-xs text-muted-foreground">{term}</dt>
      <dd className="flex flex-col gap-0.5 text-sm break-all">{children}</dd>
    </div>
  )
}
