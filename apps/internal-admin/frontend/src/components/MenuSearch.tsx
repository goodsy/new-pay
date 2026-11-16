import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent as ReactKeyboardEvent, type MouseEvent } from 'react'
import { createPortal } from 'react-dom'
import { searchMenus, type Menu, type View } from '../navigation'

/** 단축키 표시용(리뷰 NIT): Apple 기기면 ⌘, 그 밖은 Ctrl. 판정은 표시만 바꾼다(두 키 모두 늘 동작). 읽을 수 없으면 Ctrl. */
function isApplePlatform(): boolean {
  try {
    const nav = navigator as Navigator & { userAgentData?: { platform?: string } }
    return /mac|iphone|ipad|ipod/i.test(nav.userAgentData?.platform || nav.platform || '')
  } catch {
    return false
  }
}

const FOCUSABLE ='button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), a[href], [tabindex]:not([tabindex="-1"])'

/**
 * 상단 메뉴 검색(rev 0.4, D-ADMIN-UI-003): 이 역할에 보이는 메뉴·탭 이름만 찾아 이동한다. 거래·가맹점·TID 검색이 아니다(서버 기능 없음).
 * 검색어는 화면 상태에만 두고 서버로 보내지 않는다. Enter = 첫 결과.
 *
 * ADMIN-UI-WORKSPACE-001: 참고 시안 그대로 상단 바의 `.search` 버튼(⌘/Ctrl+K)이 `.overlay` 안 `.modal`을 연다. overlay는 늘 그려 두고
 * `hidden`으로만 숨긴다(원본 `#overlay[hidden]`). 원본처럼 shell 바깥(body 끝)에 둔다(portal).
 * 열려 있는 동안(리뷰 MINOR-1): overlay 밖 body 자식은 `inert`(OneTimeCode와 같은 방식 — 우리가 붙인 것만 되돌린다), Tab·Shift+Tab은 대화상자 안에서만 돈다,
 * Esc는 초점이 어디 있든 닫는다. 닫으면 검색어를 비우고 초점을 검색 버튼으로 돌려준다. 다른 modal 대화상자가 열려 있으면 ⌘/Ctrl+K로 열지 않는다.
 */
export function MenuSearch({ menus, onOpen }: { menus: Menu[]; onOpen: (view: View) => void }) {
  const [open, setOpen] = useState(false)
  const [query, setQuery] = useState('')
  const button = useRef<HTMLButtonElement>(null)
  const input = useRef<HTMLInputElement>(null)
  const overlay = useRef<HTMLDivElement>(null)
  const dialog = useRef<HTMLElement>(null)
  const wasOpen = useRef(false)
  const results = searchMenus(menus, query)
  const [shortcut] = useState(() => (isApplePlatform() ? '⌘ K' : 'Ctrl K'))

  const show = () => {
    setQuery('')
    setOpen(true)
  }
  const close = () => {
    setQuery('')
    setOpen(false)
  }

  // 배경 inert(열려 있는 동안만). layout effect라 닫힐 때 초점 복귀(아래 effect)보다 먼저 풀린다.
  useLayoutEffect(() => {
    if (!open) return
    const self = overlay.current
    const made = Array.from(document.body.children).filter((el) => el !== self && !el.hasAttribute('inert'))
    made.forEach((el) => el.setAttribute('inert', ''))
    return () => made.forEach((el) => el.removeAttribute('inert'))
  }, [open])

  useEffect(() => {
    if (open) input.current?.focus()
    else if (wasOpen.current) button.current?.focus()
    wasOpen.current = open
  }, [open])

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      // 한글 입력기가 켜져 있으면 key가 'ㅏ'가 된다 — 물리 키(code KeyK)도 받는다(리뷰 NIT).
      if (!(event.metaKey || event.ctrlKey) || (event.key.toLowerCase() !== 'k' && event.code !== 'KeyK')) return
      const otherModal = Array.from(document.querySelectorAll('[role="dialog"][aria-modal="true"]')).some((el) => el !== dialog.current)
      if (otherModal) return
      event.preventDefault()
      show()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  // Esc는 문서 전체에서(초점이 대화상자 밖으로 옮겨졌어도 닫힌다).
  useEffect(() => {
    if (!open) return
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      close()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [open])

  const go = (view: View) => {
    close()
    onOpen(view)
  }
  const submit = (event: FormEvent) => {
    event.preventDefault()
    const first = results[0]
    if (first) go(first.target)
  }
  const trapTab = (event: ReactKeyboardEvent) => {
    if (event.key !== 'Tab' || !dialog.current) return
    const items = Array.from(dialog.current.querySelectorAll<HTMLElement>(FOCUSABLE))
    if (items.length === 0) return
    const first = items[0]!
    const last = items[items.length - 1]!
    const active = document.activeElement
    if (event.shiftKey && (active === first || !dialog.current.contains(active))) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && (active === last || !dialog.current.contains(active))) {
      event.preventDefault()
      first.focus()
    }
  }
  const onBackdrop = (event: MouseEvent<HTMLDivElement>) => {
    if (event.target === event.currentTarget) close()
  }

  return (
    <>
      <button ref={button} type="button" className="search" aria-label="메뉴 검색" aria-haspopup="dialog" aria-expanded={open} onClick={show}>
        {'⌕　메뉴 검색　 '}
        <kbd>{shortcut}</kbd>
      </button>
      {createPortal(
        <div ref={overlay} className="overlay" hidden={!open} onClick={onBackdrop}>
          <section ref={dialog} className="modal" role="dialog" aria-modal="true" aria-label="메뉴 검색" onKeyDown={trapTab}>
            <button type="button" className="close" aria-label="닫기" onClick={close}>
              ×
            </button>
            <form role="search" onSubmit={submit}>
              <input
                ref={input}
                type="search"
                aria-label="메뉴 검색"
                placeholder="메뉴 이름 검색"
                autoComplete="off"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
            </form>
            {query.trim() !== '' &&
              (results.length === 0 ? (
                <div role="status" className="empty">
                  찾는 메뉴가 없어요.
                </div>
              ) : (
                <ul aria-label="메뉴 검색 결과">
                  {results.map((r) => (
                    <li key={r.path}>
                      <button type="button" className="result" onClick={() => go(r.target)}>
                        {r.path}
                      </button>
                    </li>
                  ))}
                </ul>
              ))}
          </section>
        </div>,
        document.body,
      )}
    </>
  )
}
