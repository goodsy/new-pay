import { useRef, type KeyboardEvent, type ReactNode } from 'react'
import type { Page, Tab } from '../navigation'
import { cn } from '../lib/utils'

/**
 * 메뉴 카드(rev 0.4, 참고 시안 `.card` + `.tabs`): 업무 메뉴는 카드 위쪽 탭 막대(선택 탭 = 파란 밑줄), 아래 탭 패널에 기존 화면을 그린다.
 * WAI-ARIA tabs: 선택된 탭만 Tab 순서(roving tabindex), ←/→/Home/End로 옮기면서 바로 선택. 내 업무(탭 없음)는 카드만.
 */
export function MenuCard({ label, tabs, tabbed, active, onSelect, children }: {
  label: string
  tabs: Tab[]
  tabbed: boolean
  active: Page
  onSelect: (page: Page) => void
  children: ReactNode
}) {
  const refs = useRef(new Map<Page, HTMLButtonElement>())
  const body = <div className="flex flex-col gap-6 p-5 md:p-6">{children}</div>
  if (!tabbed) return <div className="overflow-hidden rounded-[13px] border bg-card text-card-foreground">{body}</div>

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
    <div className="overflow-hidden rounded-[13px] border bg-card text-card-foreground">
      <div role="tablist" aria-label={label} className="flex gap-[26px] overflow-x-auto border-b px-[23px] whitespace-nowrap">
        {tabs.map((tab, index) => {
          const selected = tab.page === active
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
              aria-selected={selected}
              aria-controls={`panel-${tab.page}`}
              tabIndex={selected ? 0 : -1}
              onClick={() => onSelect(tab.page)}
              onKeyDown={(event) => move(event, index)}
              className={cn(
                '-mb-px border-b-2 border-transparent py-3.5 text-xs text-muted-foreground outline-none hover:text-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50',
                selected && 'border-primary font-bold text-primary hover:text-primary',
              )}
            >
              {tab.label}
            </button>
          )
        })}
      </div>
      <div role="tabpanel" id={`panel-${active}`} aria-labelledby={`tab-${active}`}>
        {body}
      </div>
    </div>
  )
}
