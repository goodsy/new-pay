import { expect } from 'vitest'

/**
 * LAYOUT-ROLLOUT B6(사용자 결정 2026-11-19 상세 일관성): 상세 패널 안 정의 목록이 사업자 조회 상세의 [DetailRow](../components/DetailCard.tsx)와 같은 줄 모양인지 본다.
 * 줄 = dl > div(grid gap-1 border-b py-2 last:border-b-0 sm:grid-cols-[140px_1fr]) > dt(text-xs text-muted-foreground) + dd(text-sm break-all).
 * dd는 세로 flex라 시안 `.tag`(inline-block)를 바로 넣으면 칸 너비로 늘어난다 — 값 모양을 지키려 `.tag`는 dd 바로 아래에 두지 않는다.
 */
export function expectDetailRows(dl: Element | null, terms: readonly string[]) {
  expect(dl, '정의 목록').not.toBeNull()
  const list = dl as Element
  expect(list.tagName.toLowerCase()).toBe('dl')
  // 바깥 dl은 열 grid가 아닌 세로 줄 목록(이름 열 140px은 각 줄이 맡는다).
  expect(list.className).not.toMatch(/grid-cols/)
  const rows = Array.from(list.children)
  expect(rows.map((row) => row.querySelector(':scope > dt')?.textContent)).toEqual([...terms])
  for (const row of rows) {
    const term = row.querySelector(':scope > dt')?.textContent ?? ''
    expect(row.tagName.toLowerCase(), term).toBe('div')
    expect(row, term).toHaveClass('grid', 'gap-1', 'border-b', 'py-2', 'last:border-b-0', 'sm:grid-cols-[140px_1fr]')
    expect(Array.from(row.children).map((c) => c.tagName.toLowerCase()), term).toEqual(['dt', 'dd'])
    expect(row.children[0], term).toHaveClass('text-xs', 'text-muted-foreground')
    expect(row.children[1], term).toHaveClass('text-sm', 'break-all')
    expect(row.querySelector(':scope > dd > .tag'), term).toBeNull()
  }
}

/** 바깥 Panel 구조(section.card[aria-label] > .cardtop > h4 + .cardbody) 그대로 — 이 Panel의 cardbody 바로 아래 dl을 돌려준다. */
export function panelDetailList(region: HTMLElement, name: string): Element | null {
  expect(region).toHaveClass('card')
  expect(region.querySelector(':scope > .cardtop > h4')).toHaveTextContent(name)
  const body = region.querySelector(':scope > .cardbody')
  expect(body).not.toBeNull()
  const lists = body!.querySelectorAll(':scope > dl')
  expect(lists).toHaveLength(1)
  return lists[0] ?? null
}
