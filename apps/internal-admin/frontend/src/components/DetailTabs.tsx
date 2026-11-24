import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react'

/** 탭 하나. [alert]가 있으면 탭 글자 옆에 표시하고 탭 설명(aria-describedby)으로도 읽힌다 — 접근 이름은 [label] 그대로(시험·보조기기가 같은 이름으로 찾는다). */
export interface DetailTab<K extends string> {
  key: K
  label: string
  alert?: string
  panel: ReactNode
}

/**
 * 상세 화면 안 탭(ADMIN-DS-001 §5 "상세 탭" 정본 — MERCHANT-IA-001 B4, D-ADMIN-UI-010 ①). 사업자 상세·가맹점 상세가 쓴다.
 * - 접근성: tablist(aria-label) / tab(aria-selected·aria-controls·roving tabIndex) / tabpanel(aria-labelledby). 좌우 화살표·Home·End로 탭을 옮기고 바로 고른다(MenuCard와 같은 자동 선택).
 * - **패널은 언마운트하지 않고 `hidden`으로 숨긴다**: 결과 불명 잠금 폼(useOneShot 정리 함수가 잠기지 않은 결과를 지운다)과 입력 중인 값이 탭을 바꿔도 남는다(명세 §7 위험과 대응).
 * - 모양: 사업자 상세 탭의 기존 모양 그대로(아래 class — 모양 불변이 이번 배치의 조건, 정본 class·토큰으로 옮기는 일은 ADMIN-DS 후속).
 * - CSP: style 속성 없음.
 */
export function DetailTabs<K extends string>({ label, tabs, selected, onSelect }: {
  label: string
  tabs: readonly DetailTab<K>[]
  selected: K
  onSelect: (key: K) => void
}) {
  const base = useId()
  const refs = useRef(new Map<K, HTMLButtonElement>())
  const tabId = (key: K) => `${base}-tab-${key}`
  const panelId = (key: K) => `${base}-panel-${key}`
  const alertId = (key: K) => `${base}-alert-${key}`

  const move = (event: KeyboardEvent<HTMLButtonElement>, index: number) => {
    const last = tabs.length - 1
    const target = event.key === 'ArrowRight' ? (index === last ? 0 : index + 1)
      : event.key === 'ArrowLeft' ? (index === 0 ? last : index - 1)
        : event.key === 'Home' ? 0
          : event.key === 'End' ? last
            : null
    if (target === null) return
    event.preventDefault()
    const tab = tabs[target]!
    onSelect(tab.key)
    refs.current.get(tab.key)?.focus()
  }

  return (
    <div className="flex flex-col gap-4">
      <div role="tablist" aria-label={label} className="flex flex-wrap gap-2 border-b">
        {tabs.map((tab, index) => {
          const on = tab.key === selected
          return (
            <button
              key={tab.key}
              ref={(el) => {
                if (el) refs.current.set(tab.key, el)
                else refs.current.delete(tab.key)
              }}
              type="button"
              role="tab"
              id={tabId(tab.key)}
              aria-selected={on}
              aria-controls={panelId(tab.key)}
              aria-label={tab.label}
              aria-describedby={tab.alert ? alertId(tab.key) : undefined}
              tabIndex={on ? 0 : -1}
              className={on ? 'border-b-2 border-primary px-3 py-2 text-sm font-semibold' : 'px-3 py-2 text-sm text-muted-foreground'}
              onClick={() => onSelect(tab.key)}
              onKeyDown={(event) => move(event, index)}
            >
              {tab.label}
              {tab.alert && <span id={alertId(tab.key)} className="ml-1 text-xs font-semibold text-destructive">{`· ${tab.alert}`}</span>}
            </button>
          )
        })}
      </div>
      {tabs.map((tab) => (
        // hidden 속성이 화면에서도 확실히 이기도록 패널 자체에는 display class를 두지 않는다(배치는 안쪽 div).
        <div key={tab.key} role="tabpanel" id={panelId(tab.key)} aria-labelledby={tabId(tab.key)} hidden={tab.key !== selected}>
          <div className="flex flex-col gap-4">{tab.panel}</div>
        </div>
      ))}
    </div>
  )
}
