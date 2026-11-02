import { useState, type FormEvent } from 'react'
import { searchMenus, type Menu, type View } from '../navigation'
import { SearchIcon } from './icons'

/**
 * 상단 메뉴 검색(rev 0.4, D-ADMIN-UI-003): 이 역할에 보이는 메뉴·탭 이름만 찾아 이동한다. 거래·가맹점·TID 검색이 아니다(서버 기능 없음).
 * 검색어는 화면 상태에만 두고 서버로 보내지 않는다. Enter = 첫 결과, Esc = 지움.
 */
export function MenuSearch({ menus, onOpen }: { menus: Menu[]; onOpen: (view: View) => void }) {
  const [query, setQuery] = useState('')
  const results = searchMenus(menus, query)
  const open = (view: View) => {
    setQuery('')
    onOpen(view)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const first = results[0]
    if (first) open(first.target)
  }
  return (
    <form role="search" onSubmit={submit} className="relative ml-auto w-full max-w-[275px]">
      <SearchIcon aria-hidden="true" className="pointer-events-none absolute top-1/2 left-3 size-3.5 -translate-y-1/2 text-muted-foreground" />
      <input
        type="search"
        aria-label="메뉴 검색"
        placeholder="메뉴 검색"
        autoComplete="off"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        onKeyDown={(e) => e.key === 'Escape' && setQuery('')}
        className="h-9 w-full rounded-[7px] bg-muted pr-3 pl-8 text-xs text-foreground outline-none placeholder:text-muted-foreground focus-visible:ring-[3px] focus-visible:ring-ring/50"
      />
      {query.trim() !== '' && (
        <div className="absolute top-full right-0 left-0 z-20 mt-1 overflow-hidden rounded-lg border bg-popover text-popover-foreground shadow-md">
          {results.length === 0 ? (
            <p role="status" className="px-3 py-2.5 text-xs text-muted-foreground">찾는 메뉴가 없어요.</p>
          ) : (
            <ul aria-label="메뉴 검색 결과" className="flex flex-col py-1">
              {results.map((r) => (
                <li key={r.path}>
                  <button type="button" onClick={() => open(r.target)} className="w-full px-3 py-2 text-left text-xs outline-none hover:bg-muted focus-visible:bg-muted">
                    {r.path}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </form>
  )
}
