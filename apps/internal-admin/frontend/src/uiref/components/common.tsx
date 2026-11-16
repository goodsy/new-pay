import type { ReactNode } from 'react'
import { badgeTone, pipelineSteps } from '../data'

/** app.js badge() */
export function StatusBadge({ s }: { s: string }) {
  return <span className={`tag ${badgeTone(s)}`}>{s}</span>
}

/** app.js head() — eyebrow·제목·설명 / 오른쪽 actions(비어 있어도 div는 남긴다) */
export function PageHeading({ t, s, children }: { t: string; s: string; children?: ReactNode }) {
  return (
    <div className="heading">
      <div>
        <div className="eyebrow">PAYMENT OPERATIONS</div>
        <h1>{t}</h1>
        <p>{s}</p>
      </div>
      <div className="actions">{children}</div>
    </div>
  )
}

export type Stat = readonly [string, string, string, ReactNode]

/** app.js stats() */
export function Stats({ items }: { items: readonly Stat[] }) {
  return (
    <div className="stats">
      {items.map((x) => (
        <div className="stat" key={x[0]}>
          <div className="stitle">{x[0]}</div>
          <strong>
            {x[1]}
            <small>{x[2]}</small>
          </strong>
          <div className="hint">{x[3]}</div>
        </div>
      ))}
    </div>
  )
}

/** app.js pipeline() */
export function Pipeline() {
  return (
    <div className="pipeline">
      {pipelineSteps.map((r) => (
        <div className="pipe" key={r[1]}>
          <span className="stepno">{r[0]}</span>
          <div>
            <b>{r[1]}</b>
            <small>{r[2]}</small>
          </div>
          <StatusBadge s={r[3]} />
        </div>
      ))}
    </div>
  )
}
