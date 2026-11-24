import { useId, type FormEvent, type ReactNode } from 'react'
import { cn } from '../lib/utils'

/**
 * 목록 필터 묶음(MERCHANT-IA-001 B6 — 사용자 결정 D-ADMIN-UI-011 ①, pg-lab 목록 패턴): 검색 조건을 한 카드 안에 '항목명 | 입력' 칸으로 묶고(넓은 폭 2열, 1100px 이하 1열),
 * 묶음 아래 오른쪽에 '초기화'·'검색'. 배치는 workspace.css `.filterpanel`·`.filtergrid`·`.filterfield`·`.filterlabel`·`.filtercontrol`·`.filtererror`·`.filteractions`(기존 토큰만 — 새 색 없음).
 *
 * - '검색'은 폼 submit([onSubmit] — 검색 조건·호출은 화면이 그대로 정한다). '초기화'는 type=button으로 [onReset]만 부른다(입력·결과를 비우는 화면 동작 — 서버 호출은 화면이 하지 않는다).
 * - '검색'은 [busy] 동안 막는다(두 번 보내지 않게). '초기화'도 기본은 막고, 진행 중 응답을 버릴 수 있는 화면만 [resetWhileBusy]로 연다.
 * - 입력 칸은 [FilterField]. 이름은 진짜 `<label for>`(입력 id를 children에 넘긴다), 오류는 칸 아래 글자 + aria-describedby.
 */
export function FilterPanel({ label, onSubmit, onReset, busy = false, resetWhileBusy = false, inset = false, children }: {
  /** 폼 이름(예: '가맹점 검색'). */
  label: string
  onSubmit: (event: FormEvent) => void
  onReset: () => void
  busy?: boolean
  /** 검색 중에도 '초기화'를 허용한다 — 화면이 진행 중 응답을 버릴 수 있을 때만(요청 순번이 있는 화면, B6 리뷰 NIT-3). 기본은 검색 중 막음. */
  resetWhileBusy?: boolean
  /** 카드 가장자리까지 그리는 화면(flush — MenuCard)에서는 카드 안쪽 여백(좌우 23px)에 맞춘다. */
  inset?: boolean
  children: ReactNode
}) {
  return (
    <form aria-label={label} noValidate onSubmit={onSubmit} className={cn('filterpanel', inset && 'inset')}>
      <div className="filtergrid">{children}</div>
      <div className="filteractions">
        <button type="button" className="btn" onClick={onReset} disabled={busy && !resetWhileBusy}>초기화</button>
        <button type="submit" className="btn primary" disabled={busy} aria-busy={busy}>검색</button>
      </div>
    </form>
  )
}

/**
 * 필터 묶음의 칸 하나: 왼쪽 이름, 오른쪽 입력. [group]이면 입력이 여럿(예: 등록일 시작~끝)이라 이름을 role=group의 이름으로 잇고(각 입력은 자기 aria-label),
 * 아니면 `<label for>`. [error]가 있으면 입력 아래 오류 글자(id = [errorId] 또는 자동) — children 둘째 인자 describedBy로 받아 입력의 aria-describedby에 붙인다.
 * [wide]는 넓은 폭에서도 한 줄 전체(두 칸 자리)를 쓴다.
 */
export function FilterField({ label, hint, error, errorId, describedBy, group = false, wide = false, children }: {
  label: string
  /** 칸 아래 늘 보이는 짧은 설명(B6 리뷰 M-1 — 예: 가맹점 코드 '예전 코드도 찾음'). aria-describedby로 잇는다. */
  hint?: string
  error?: string | null
  /** 오류 글자 id를 다른 칸에서도 가리켜야 할 때(예: 가맹점 코드 '함께 쓸 수 없음'). 없으면 자동. */
  errorId?: string
  /** 칸 밖의 안내(묶음 아래 한 줄 등) id — 오류와 함께 aria-describedby로 잇는다. */
  describedBy?: string
  group?: boolean
  wide?: boolean
  children: (id: string, describedBy: string | undefined) => ReactNode
}) {
  const base = useId()
  const inputId = `${base}-input`
  const labelId = `${base}-label`
  const ownErrorId = errorId ?? `${base}-error`
  const hintId = `${base}-hint`
  const described = [error ? ownErrorId : null, hint ? hintId : null, describedBy ?? null].filter((v): v is string => v !== null).join(' ') || undefined
  return (
    <div className={cn('filterfield', wide && 'wide')}>
      {group
        ? <span id={labelId} className="filterlabel">{label}</span>
        : <label htmlFor={inputId} className="filterlabel">{label}</label>}
      <div className="filtercontrol" role={group ? 'group' : undefined} aria-labelledby={group ? labelId : undefined}>
        {children(inputId, described)}
        {error && <p id={ownErrorId} className="filtererror">{error}</p>}
        {hint && <p id={hintId} className="filterhint">{hint}</p>}
      </div>
    </div>
  )
}

