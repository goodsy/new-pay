import { useCallback, useEffect, useRef, useState } from 'react'
import { isPageId } from '../data/menus'
import type { PageId } from '../data/types'

/**
 * 사내 해시 라우터(의존성 추가 없음 — kit 의 react-router-dom 대체). 원본 app.js 와 같은 해시 URL(#home … #map)을 쓴다.
 * - 잘못된/빈 해시 → #home 으로 replace(기록을 남기지 않음)
 * - navigate(id): 해시 갱신 + 이동 순번(seq) 증가. 같은 페이지로 가도 seq 가 올라 ModulePage 가 재마운트된다
 *   (원본 go() 는 같은 페이지여도 tab·query·status 를 초기화한다).
 * - 브라우저 뒤로/앞으로(hashchange): 다른 유효 페이지일 때만 반영(원본 hashchange 처리와 같음).
 */
export interface HashRoute {
  page: PageId
  seq: number
}

function readHash(): PageId | null {
  const p = window.location.hash.slice(1)
  return isPageId(p) ? p : null
}

function replaceWithHome(): void {
  window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}#home`)
}

function initialRoute(): HashRoute {
  const p = readHash()
  if (p === null) replaceWithHome()
  return { page: p ?? 'home', seq: 0 }
}

export function useHashRoute(): [HashRoute, (id: PageId) => void] {
  const [route, setRoute] = useState<HashRoute>(initialRoute)
  const pageRef = useRef(route.page)
  pageRef.current = route.page

  useEffect(() => {
    const onHashChange = () => {
      const p = readHash()
      if (p === null) replaceWithHome()
      const next = p ?? 'home'
      if (next !== pageRef.current) setRoute((r) => ({ page: next, seq: r.seq + 1 }))
    }
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  const navigate = useCallback((id: PageId) => {
    pageRef.current = id
    if (window.location.hash !== `#${id}`) window.location.hash = id
    setRoute((r) => ({ page: id, seq: r.seq + 1 }))
  }, [])

  return [route, navigate]
}
