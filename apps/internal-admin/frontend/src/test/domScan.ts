/**
 * DOM 전체에서 [needle]이 나오는 곳 목록(MERCHANT-CODE-001 MC-2b — D-MCODE-003·D-ADMIN-UI-004 ③ 시험용).
 * 보는 곳: 텍스트 노드, root 전체 텍스트(쪼개진 텍스트 노드), 모든 요소의 모든 속성(value·placeholder·title·aria-*·data-* 포함), 입력·선택·여러 줄 입력란의 value 프로퍼티.
 * 위치 = `<가장 가까운 aria-label 구역>/<label 첫 span 또는 tag>/<종류>` — 목록이 비어 있지 않으면 어디서 새는지 바로 보인다.
 */
export function domOccurrences(root: Element, needle: string): string[] {
  const found = new Set<string>()
  const where = (el: Element) => `${el.closest('section[aria-label]')?.getAttribute('aria-label') ?? '-'}/${el.closest('label')?.querySelector('span')?.textContent ?? el.tagName}`
  for (const el of [root, ...Array.from(root.querySelectorAll('*'))]) {
    for (const attr of Array.from(el.attributes)) if (attr.value.includes(needle)) found.add(`${where(el)}/${attr.name === 'value' ? 'value' : `@${attr.name}`}`)
    if ((el instanceof HTMLInputElement || el instanceof HTMLSelectElement || el instanceof HTMLTextAreaElement) && el.value.includes(needle)) found.add(`${where(el)}/value`)
    for (const node of Array.from(el.childNodes)) {
      if (node.nodeType === Node.TEXT_NODE && node.textContent?.includes(needle)) found.add(`${where(el)}/text:${node.textContent}`)
    }
  }
  // 리뷰 NIT-1: 여러 텍스트 노드로 쪼개진 값(예: "mer" + "_…")도 잡도록 전체 텍스트를 한 번 더 본다.
  if ((root.textContent ?? '').includes(needle)) found.add(`${where(root)}/textContent`)
  return [...found].sort()
}
