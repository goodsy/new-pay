import { useId, type InputHTMLAttributes, type ReactNode } from 'react'

/** .timeline — 좌측 세로선 + 항목(p) + 보조설명(small) */
export function Timeline({ items }: { items: readonly { title: ReactNode; desc?: ReactNode }[] }) {
  return (
    <div className="timeline">
      {items.map((it, i) => (
        <p key={i}>
          {it.title}
          {it.desc !== undefined && <small>{it.desc}</small>}
        </p>
      ))}
    </div>
  )
}

/** .detailgrid — 2열 라벨/값 그리드 (상세 패널, 위저드 검토 단계) */
export function DetailGrid({ items }: { items: readonly { label: string; value: ReactNode }[] }) {
  return (
    <div className="detailgrid">
      {items.map((x) => (
        <div key={x.label}>
          <label>{x.label}</label>
          <strong>{x.value}</strong>
        </div>
      ))}
    </div>
  )
}

/** .notice — 회색 안내 박스 (데모/정책 유의사항) */
export function Notice({ children }: { children: ReactNode }) {
  return <div className="notice">{children}</div>
}

/**
 * .formrow — 세로 라벨 + 풀폭 입력.
 * htmlFor: 라벨과 입력을 연결한다(원본은 연결 없음 — 접근성·시험용 추가, 시각 영향 없음).
 */
export function FormRow({ label, htmlFor, children }: { label: string; htmlFor?: string; children: ReactNode }) {
  return (
    <div className="formrow">
      <label htmlFor={htmlFor}>{label}</label>
      {children}
    </div>
  )
}

export function TextField({ label, id, ...rest }: InputHTMLAttributes<HTMLInputElement> & { label: string }) {
  const generated = useId()
  const inputId = id ?? generated
  return (
    <FormRow label={label} htmlFor={inputId}>
      <input id={inputId} {...rest} />
    </FormRow>
  )
}

/** .wizard — 4단계 진행 바. index 이하 단계는 .current(파란 상단선) */
export function WizardSteps({ labels, current }: { labels: readonly string[]; current: number }) {
  return (
    <div className="wizard">
      {labels.map((x, i) => (
        <span key={x} className={i <= current ? 'current' : ''}>
          {`${i + 1}. ${x}`}
        </span>
      ))}
    </div>
  )
}

/**
 * 패널 하단 공통 고지. 원본은 style="margin-top:28px" — inline style 대신 overrides.css(.drawer > p.subtle:last-child)가 같은 값을 준다.
 * Drawer 본문의 마지막 자식이어야 한다.
 */
export function DemoFootnote() {
  return <p className="subtle">화면 시연용 데이터 · 실제 시스템에 반영되지 않습니다.</p>
}
