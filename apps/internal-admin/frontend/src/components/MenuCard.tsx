import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import { selectedTabOf, type Page, type Subpage, type Tab } from '../navigation'

/**
 * 메뉴 카드(ADMIN-UI-WORKSPACE-001, 참고 시안 `section.card` > `.tabs` — 선택 탭 class `on`): 업무 메뉴는 카드 위쪽 탭 막대, 아래 탭 패널에 화면을 그린다.
 * WAI-ARIA tabs: 선택된 탭만 Tab 순서(roving tabindex), ←/→/Home/End로 옮기면서 바로 선택. 내 업무(탭 없음)·탭이 아닌 화면은 카드만.
 * [flush] — 시안 구조(`.filters`·`.tablewrap`·`.tablebottom` …)로 옮긴 화면은 탭 패널 안에 바로 그린다(카드 가장자리까지). 아직 옮기지 않은 화면은 안쪽 여백 칸에 그린다.
 */
export function MenuCard({ label, tabs, subpages, tabbed, active, onSelect, flush = false, children }: {
  label: string
  tabs: Tab[]
  /** 탭이 아닌 하위 화면(D-ADMIN-UI-006 — 목록 버튼이 같은 탭 안에서 연 등록 화면): 열려 있으면 부모 탭이 선택이고 tabpanel도 그 탭에 연결된다. */
  subpages?: Subpage[]
  tabbed: boolean
  active: Page
  onSelect: (page: Page) => void
  flush?: boolean
  children: ReactNode
}) {
  const refs = useRef(new Map<Page, HTMLButtonElement>())
  const body = flush ? children : <div className="flex flex-col gap-6 p-5 md:p-6">{children}</div>
  const selectedTab = selectedTabOf(tabs, subpages, active)
  // 탭이 아닌 화면(쪽 머리 주요 버튼이 연 화면 — ADMIN-UI-MERCHANT-001)은 탭 막대 없이 카드만: 선택된 탭이 없는 tablist를 그리지 않는다.
  if (!tabbed || !selectedTab) return <section className="card">{body}</section>

  const move = (event: KeyboardEvent, index: number) => {
    const last = tabs.length - 1
    const next = { ArrowRight: index === last ? 0 : index + 1, ArrowLeft: index === 0 ? last : index - 1, Home: 0, End: last }[event.key]
    const tab = next === undefined ? undefined : tabs[next]
    if (!tab) return
    event.preventDefault()
    onSelect(tab.page)
    refs.current.get(tab.page)?.focus()
  }

  return (
    <section className="card">
      <div className="tabs" role="tablist" aria-label={label}>
        {tabs.map((tab, index) => {
          const selected = tab.page === selectedTab.page
          return (
            <button
              key={tab.page}
              ref={(el) => {
                if (el) refs.current.set(tab.page, el)
                else refs.current.delete(tab.page)
              }}
              type="button"
              role="tab"
              id={`tab-${tab.page}`}
              className={selected ? 'on' : undefined}
              aria-selected={selected}
              // tabpanel은 선택 탭 것 하나만 그리므로 선택 탭만 가리킨다(없는 id 참조 없음 — 리뷰 NIT).
              aria-controls={selected ? `panel-${tab.page}` : undefined}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(tab.page)}
              onKeyDown={(event) => move(event, index)}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
      <div role="tabpanel" id={`panel-${selectedTab.page}`} aria-labelledby={`tab-${selectedTab.page}`}>
        {body}
      </div>
    </section>
  )
}
