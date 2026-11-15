import { describe, expect, it } from 'vitest'
import { domOccurrences } from './domScan'

/** domOccurrences 자체 시험(MC-2b 리뷰 NIT-1 — 쪼개진 텍스트 노드·속성·입력 value). Demo 문자열만. */
describe('domOccurrences', () => {
  it('finds a needle split across text nodes, in any attribute and in an input value property', () => {
    const root = document.createElement('section')
    root.setAttribute('aria-label', '구역')
    const p = document.createElement('p')
    p.append(document.createTextNode('mer'), document.createTextNode('_000'))
    root.append(p)
    expect(domOccurrences(root, 'mer_')).toEqual(['구역/SECTION/textContent'])

    const span = document.createElement('span')
    span.setAttribute('title', 'biz_1')
    const input = document.createElement('input')
    input.value = 'biz_2'
    root.append(span, input)
    expect(domOccurrences(root, 'biz_')).toEqual(['구역/INPUT/value', '구역/SPAN/@title'])
  })

  it('returns an empty list when the needle is absent', () => {
    const root = document.createElement('div')
    root.textContent = '소속 가맹점: 데모 가맹점 · shopabcd12'
    expect(domOccurrences(root, 'mer_')).toEqual([])
  })
})
