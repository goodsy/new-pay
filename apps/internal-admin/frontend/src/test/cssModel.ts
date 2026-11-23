import { readFileSync } from 'node:fs'

/**
 * 디자인 시스템 CSS 읽기 도우미(ADMIN-DS-B1 — ADMIN-DS-001 §7.3). 원문 문자열·줄 순서를 대조하지 않고 규칙(선택자 → 선언)으로 읽어,
 * 토큰 값·리터럴 위치·다크 선택자·대비를 계산한다. 시험 전용(src/test) — 새 도구·의존성 없음.
 */
export const DESIGN_CSS_PATH = 'src/workspace.css' // Vitest 작업 디렉터리 = frontend/. 파일 이름은 B1에서 유지하기로 했다(ADMIN-DS-001 §11).
export const readCss = (path: string) => readFileSync(path, 'utf8')

export interface CssRule {
  /** 쉼표로 나눈 선택자(괄호 안 쉼표는 나누지 않음), 앞뒤 공백 제거. */
  selectors: string[]
  /** 선언(속성 → 값). 같은 속성이 두 번이면 뒤 값. */
  decls: Record<string, string>
  /** 감싼 @media 조건(없으면 null). */
  media: string | null
  /** 규칙 바로 앞(공백 제외)이 주석으로 끝나는지 — 이유 주석이 붙은 예외 확인용. */
  commented: boolean
}

const splitTop = (text: string, sep: string) => {
  const out: string[] = []
  let depth = 0
  let cur = ''
  for (const ch of text) {
    if (ch === '(') depth++
    if (ch === ')') depth--
    if (ch === sep && depth === 0) {
      out.push(cur)
      cur = ''
    } else cur += ch
  }
  out.push(cur)
  return out.map((s) => s.trim()).filter((s) => s !== '')
}

/** @layer·@media 안을 펼친 스타일 규칙 목록(파일 순서). */
export function parseRules(css: string): CssRule[] {
  const rules: CssRule[] = []
  const stack: { prelude: string; commented: boolean }[] = []
  let buf = ''
  let i = 0
  while (i < css.length) {
    if (css.startsWith('/*', i)) {
      const end = css.indexOf('*/', i + 2)
      if (end < 0) throw new Error(`unclosed comment at offset ${i}`)
      // 주석 자리 표시(앞에 주석이 있었는지만 남긴다). 빈 주석(/**/)은 이유 주석이 아니다.
      if (css.slice(i + 2, end).trim() !== '') buf += '\u0000'
      i = end + 2
      continue
    }
    const ch = css[i]!
    if (ch === '{') {
      const segment = buf.split(';').pop()!
      // 선택자 글자 앞(공백만 사이에 둔 곳)에 주석 자리 표시가 있으면 이유 주석이 붙은 규칙이다.
      const lead = segment.slice(0, Math.max(0, segment.search(/[^\s\u0000]/)))
      const commented = lead.includes('\u0000')
      stack.push({ prelude: segment.replace(/\u0000/g, '').trim(), commented })
      buf = ''
    } else if (ch === '}') {
      const top = stack.pop()!
      if (!top.prelude.startsWith('@')) {
        const decls: Record<string, string> = {}
        for (const d of splitTop(buf.replace(/\u0000/g, ''), ';')) {
          const at = d.indexOf(':')
          if (at > 0) decls[d.slice(0, at).trim()] = d.slice(at + 1).trim()
        }
        const media = stack.filter((s) => s.prelude.startsWith('@media')).map((s) => s.prelude).join(' ') || null
        rules.push({ selectors: splitTop(top.prelude, ','), decls, media, commented: top.commented })
      }
      buf = ''
    } else buf += ch
    i++
  }
  return rules
}

export type Theme = 'light' | 'dark'
export const isTokenBlock = (r: CssRule) => r.media === null && r.selectors.length === 1 && (r.selectors[0] === ':root' || r.selectors[0] === ':root[data-theme=dark]') && Object.keys(r.decls).every((k) => k.startsWith('--ds-'))

/** 토큰 정의부(맨 앞 `:root`·`:root[data-theme=dark]`) → 테마별 값. 다크는 라이트 위에 재정의를 덮은 값. */
export function designTokens(rules: CssRule[]): { light: Record<string, string>; darkOverrides: Record<string, string>; resolve: (theme: Theme, name: string) => string } {
  const light: Record<string, string> = {}
  const darkOverrides: Record<string, string> = {}
  for (const r of rules.filter(isTokenBlock)) Object.assign(r.selectors[0] === ':root' ? light : darkOverrides, r.decls)
  const resolve = (theme: Theme, name: string) => {
    const value = theme === 'dark' && name in darkOverrides ? darkOverrides[name] : light[name]
    if (value === undefined) throw new Error(`token ${name} missing`)
    return value
  }
  return { light, darkOverrides, resolve }
}

/**
 * 선택자 하나(파일에 적힌 그대로, 쉼표 목록 안 한 항목)의 실제 선언 값 — @media 밖에서 그 선택자를 가진 마지막 규칙(같은 선택자는 뒤 규칙이 이긴다).
 * 없으면 던진다(조용한 통과 없음).
 */
export function declOf(rules: CssRule[], selector: string, prop: string): string {
  let found: string | null = null
  for (const r of rules) if (r.media === null && r.selectors.includes(selector) && prop in r.decls) found = r.decls[prop]!
  if (found === null) throw new Error(`${prop} for ${selector} missing`)
  return found
}

/** `var(--ds-x)` 하나로 된 값을 그 테마의 토큰 값으로. 토큰이 아니면 던진다(색은 토큰만 — §4.1). */
export function tokenValue(tokens: ReturnType<typeof designTokens>, theme: Theme, value: string): string {
  const m = value.match(/^var\((--ds-[\w-]+)\)$/)
  if (!m) throw new Error(`not a single design token: ${value}`)
  return tokens.resolve(theme, m[1]!)
}

/** WCAG 2.x 상대 휘도·대비(sRGB #rgb·#rrggbb·#rrggbbaa — 알파는 쓰지 않는다: 반투명 색은 대비 쌍에 넣지 않는다). */
export const rgb = (hex: string): [number, number, number] => {
  const h = hex.slice(1)
  const full = h.length === 3 ? [...h].map((c) => c + c).join('') : h.slice(0, 6)
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16)) as [number, number, number]
}
const lum = ([r, g, b]: number[]) => {
  const c = [r!, g!, b!].map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!
}
export const contrast = (a: string, b: string) => {
  const [x, y] = [lum(rgb(a)), lum(rgb(b))].sort((p, q) => q - p) as [number, number]
  return (x + 0.05) / (y + 0.05)
}
