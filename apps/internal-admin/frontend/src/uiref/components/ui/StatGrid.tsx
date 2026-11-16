import type { ReactNode } from 'react'

export interface StatItem {
  title: string
  value: string
  unit: string
  hint: ReactNode
}

/** .stats > .stat — KPI 4열 그리드 (≤720px 2열). 값은 tabular-nums */
export function StatGrid({ items }: { items: readonly StatItem[] }) {
  return (
    <div className="stats">
      {items.map((x) => (
        <div className="stat" key={x.title}>
          <div className="stitle">{x.title}</div>
          <strong>
            {x.value}
            <small>{x.unit}</small>
          </strong>
          <div className="hint">{x.hint}</div>
        </div>
      ))}
    </div>
  )
}
