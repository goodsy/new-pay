import { describe, expect, it } from 'vitest'
import { contrast, declOf, designTokens, DESIGN_CSS_PATH, isTokenBlock, parseRules, readCss, rgb, tokenValue, type CssRule, type Theme } from '../test/cssModel'

/**
 * ADMIN-DS-B1 — 디자인 시스템 CSS 시험(ADMIN-DS-001 §7.3 B1 4종 + §4.1·§4.3 토큰 규칙). 원문 줄·순서를 대조하지 않고 규칙 모델로 계산한다.
 * §7.1 대비 쌍(theme.test MINOR-1·2·6, 표 칸 라이트·다크, WorkspaceShell 라이트 대비 보정값)은 모두 아래 PAIRS에 들어 있다.
 */
const CSS = readCss(DESIGN_CSS_PATH)
const RULES = parseRules(CSS)
const TOKENS = designTokens(RULES)
const STYLES_RULES = parseRules(readCss('src/styles.css'))
const THEMES: Theme[] = ['light', 'dark']
const COMPONENT_RULES = RULES.filter((r) => !isTokenBlock(r))
const isScale = (name: string) => /^--ds-(fs|radius)-/.test(name)
const colorTokens = Object.keys(TOKENS.light).filter((n) => !isScale(n) && n !== '--ds-shadow-drawer')
const t = (theme: Theme, name: string) => TOKENS.resolve(theme, name.startsWith('--ds-') ? name : `--ds-${name}`)

/** styles.css shadcn token(B5까지 남는 예외 — §4.1): 테마별 값(다크 = 라이트 위에 `:root[data-theme="dark"]` 재정의). */
const block = (selector: string) => Object.assign({}, ...STYLES_RULES.filter((r) => r.media === null && r.selectors.length === 1 && r.selectors[0] === selector).map((r) => r.decls)) as Record<string, string>
const SHADCN = { light: block(':root'), dark: { ...block(':root'), ...block(':root[data-theme="dark"]') } }
const shadcn = (theme: Theme, name: string): string => {
  const value = SHADCN[theme][name]
  if (value === undefined) throw new Error(`styles.css token ${name} missing`)
  const ref = value.match(/^var\((--[\w-]+)\)$/)
  return ref ? shadcn(theme, ref[1]!) : value
}
const mix = (fg: string, alpha: number, bg: string) => '#' + rgb(fg).map((v, i) => Math.round(v * alpha + rgb(bg)[i]! * (1 - alpha)).toString(16).padStart(2, '0')).join('')

/**
 * 대비 쌍(§4.3 표 — 토큰은 전체 이름으로 적는다: 짧은 이름(text-muted 등)은 Tailwind가 소스에서 utility 후보로 읽어 번들에 class를 더한다): 글자 4.5:1, 큰 글자·장식 글리프·식별 경계·초점 링 3:1. [글자/선 토큰, 바탕 토큰, 기준, 쓰는 곳].
 * 새 색 토큰은 여기(또는 아래 NOT_A_PAIR)에 들어가야 한다(§4.3 새 토큰 조건 3).
 */
const PAIRS: [string, string, number, string][] = [
  ['--ds-text-heading', '--ds-surface', 4.5, 'h1·h2·카드 제목·표 td strong(§7.1 theme:229·238)·상세 값(dd)'],
  ['--ds-text-heading', '--ds-canvas', 4.5, '쪽 제목 h1'],
  ['--ds-text-body', '--ds-surface', 4.5, '본문·표 td(§7.1 theme:229·238)·입력 글자'],
  ['--ds-text-body', '--ds-canvas', 4.5, '쪽 본문'],
  ['--ds-text-body', '--ds-input-bg', 4.5, '입력 글자'],
  ['--ds-text-body', '--ds-row-hover', 4.5, '선택·hover 행 글자'],
  ['--ds-text-secondary', '--ds-surface', 4.5, '메뉴·탭·카드 버튼·상세 이름(dt)·label·상단 바'],
  ['--ds-text-secondary', '--ds-table-head', 4.5, '표 머리 th'],
  ['--ds-text-secondary', '--ds-surface-subtle', 4.5, '테마 선택 버튼'],
  ['--ds-text-workspace', '--ds-surface-subtle', 4.5, '워크스페이스 상자'],
  ['--ds-text-quiet', '--ds-surface-muted', 4.5, '메뉴 검색 단추'],
  ['--ds-text-quiet', '--ds-surface', 4.5, 'KPI 대기 문구(.stat strong.pending)'],
  ['--ds-text-muted', '--ds-surface', 4.5, '보조 글자·td small·.mapnote(§7.1 theme:229·238)·.detailarea h3 small(§7.1 MINOR-2)'],
  ['--ds-text-muted', '--ds-canvas', 4.5, '쪽 설명·footer'],
  ['--ds-text-muted', '--ds-surface-subtle', 4.5, '.notice 글자'],
  ['--ds-text-muted', '--ds-row-hover', 4.5, 'hover 행 보조 글자'],
  ['--ds-text-muted-strong', '--ds-surface-subtle', 4.5, '워크스페이스 작은 글자(라이트 대비 보정)'],
  ['--ds-text-muted-strong', '--ds-surface-muted', 4.5, 'kbd(라이트 대비 보정)'],
  ['--ds-text-muted-strong', '--ds-brand-soft', 4.5, '안내 띠 설명(라이트 대비 보정)'],
  ['--ds-placeholder', '--ds-input-bg', 4.5, '입력 placeholder'],
  // MERCHANT-IA-001 B6 리뷰 NIT-4(검사 추가만): 목록 필터 묶음 칸 안(.filtercontrol 바탕 = --ds-input-bg)의 오류·설명 글자.
  ['--ds-tone-danger-fg', '--ds-input-bg', 4.5, '필터 묶음 칸 오류(.filtererror)'],
  ['--ds-text-muted', '--ds-input-bg', 4.5, '필터 묶음 칸 설명(.filterhint)·기간 ~'],
  ['--ds-eyebrow', '--ds-canvas', 4.5, 'eyebrow'],
  ['--ds-brand-fg', '--ds-brand', 4.5, '주 버튼 글자(라이트 대비 보정)'],
  ['--ds-brand-text', '--ds-surface', 4.5, '선택 탭·진행 단계·.positive'],
  ['--ds-brand-text', '--ds-brand-soft', 4.5, '선택 메뉴·테마 선택'],
  ['--ds-banner-action', '--ds-brand-soft', 4.5, '안내 띠 단추(라이트 대비 보정)'],
  ['--ds-tone-neutral-fg', '--ds-tone-neutral-bg', 4.5, '.tag'],
  ['--ds-tone-info-fg', '--ds-tone-info-bg', 4.5, '.tag.blue·.count'],
  ['--ds-tone-warning-fg', '--ds-tone-warning-bg', 4.5, '.tag.orange'],
  ['--ds-tone-success-fg', '--ds-tone-success-bg', 4.5, '.tag.green'],
  ['--ds-tone-danger-fg', '--ds-tone-danger-bg', 4.5, '.tag.red'],
  ['--ds-tone-danger-fg', '--ds-surface', 4.5, '카드 안 오류 글자(.cardnote .error·.hint.error)'],
  ['--ds-tone-success-step-fg', '--ds-tone-success-step-bg', 4.5, '단계 번호(라이트 대비 보정)'],
  ['--ds-toast-fg', '--ds-toast-bg', 4.5, 'toast'],
  ['--ds-nav-badge-fg', '--ds-nav-badge-bg', 4.5, '메뉴 배지'],
  ['--ds-logo-fg', '--ds-logo-bg', 3, '로고 글리프(30px 굵은 글자)'],
  ['--ds-brand-line', '--ds-surface', 3, '안내 띠 기호·선택 탭 밑줄'],
  ['--ds-tone-info-glyph', '--ds-tone-info-bg', 3, '파란 할 일 글리프'],
  ['--ds-tone-warning-glyph', '--ds-tone-warning-glyph-bg', 3, '주황 할 일 글리프(라이트 대비 보정)'],
  ['--ds-focus-ring', '--ds-surface', 3, '초점 링'],
  ['--ds-focus-ring', '--ds-canvas', 3, '초점 링'],
  ['--ds-focus-ring', '--ds-input-bg', 3, '입력 초점 링'],
  ['--ds-focus-ring', '--ds-brand-soft', 3, '선택 메뉴 초점 링'],
  ['--ds-control-border', '--ds-input-bg', 3, '입력 경계(SC 1.4.11)'],
  ['--ds-control-border', '--ds-surface', 3, '카드 위 입력 경계'],
  ['--ds-control-border-btn', '--ds-surface', 3, '버튼 경계'],
]

/**
 * 알려진 미달(§4.3): 값은 B1에서 바꾸지 않는다. 목록에 새 항목을 더하는 것은 리뷰 지적 대상이다. 보정 배치에서 값을 고친 뒤 비운다.
 * 각 항목은 실제로 아직 미달이어야 한다(고쳤는데 남은 항목도 실패).
 */
const KNOWN_SHORTFALLS: { pair: string; theme: Theme; followUp: string }[] = [
  { pair: '--ds-control-border / --ds-input-bg', theme: 'light', followUp: 'V1' },
  { pair: '--ds-control-border / --ds-input-bg', theme: 'dark', followUp: 'V1' },
  { pair: '--ds-control-border / --ds-surface', theme: 'light', followUp: 'V1' },
  { pair: '--ds-control-border / --ds-surface', theme: 'dark', followUp: 'V1' },
  { pair: '--ds-control-border-btn / --ds-surface', theme: 'light', followUp: 'V1' },
  { pair: '--ds-control-border-btn / --ds-surface', theme: 'dark', followUp: 'V1' },
  // B1 대비 계산에서 처음 드러난 기존 값(.notice 12px 글자 / 바탕 = 4.47:1). Coordinator 결정: 목록 추가 수용, V1 범위에 포함(값 변경, Demo 사용자 확인) — ADMIN-DS-001 §11.1.
  { pair: '--ds-text-muted / --ds-surface-subtle', theme: 'light', followUp: 'V1 범위에 포함(값 변경, Demo 사용자 확인)' },
]

/** 대비 쌍이 아닌 색 토큰(이유): 장식 선(SC 1.4.11 대상 아님)·반투명 scrim·시안 그대로 둔 표 안 선택 상자 강조색. */
const NOT_A_PAIR: Record<string, string> = {
  '--ds-line': '장식 구분선·카드 경계',
  '--ds-line-row': '표 행 구분선',
  '--ds-line-soft': '할 일 행 구분선',
  '--ds-line-timeline': 'timeline 세로선',
  '--ds-line-step': '진행 단계 윗선(현재 단계는 brand-line)',
  '--ds-banner-border': '안내 띠 경계(장식)',
  '--ds-overlay-dim': '반투명 scrim',
  '--ds-accent-control': '표 안 선택 상자 accent-color(브라우저가 그리는 체크 표시)',
}

describe('ADMIN-DS-B1 §7.3 — token contrast (WCAG 2.1 AA, light and dark)', () => {
  it('every pair meets its threshold except the named shortfall list, and every listed shortfall is still real', () => {
    const failing: string[] = []
    for (const [fg, bg, min] of PAIRS) for (const theme of THEMES) {
      if (contrast(t(theme, fg), t(theme, bg)) < min) failing.push(`${fg} / ${bg} ${theme}`)
    }
    expect(failing.sort()).toEqual(KNOWN_SHORTFALLS.map((s) => `${s.pair} ${s.theme}`).sort())
  })

  it('every colour token has light and dark values and is in a contrast pair or the named non-pair list (§4.3 new-token rule)', () => {
    for (const name of colorTokens) expect(TOKENS.darkOverrides, name).toHaveProperty([name])
    for (const name of Object.keys(TOKENS.darkOverrides)) expect(TOKENS.light, name).toHaveProperty([name])
    const paired = new Set(PAIRS.flatMap(([fg, bg]) => [fg, bg]))
    expect(colorTokens.filter((n) => !paired.has(n) && !(n in NOT_A_PAIR))).toEqual([])
    expect(Object.keys(NOT_A_PAIR).filter((n) => paired.has(n))).toEqual([])
  })

  it('§7.1 theme:229·238 pairs, computed from the rules that paint them: table cells and .mapnote on the card (light and dark)', () => {
    for (const theme of THEMES) {
      const card = tokenValue(TOKENS, theme, declOf(RULES, '.card', 'background'))
      for (const sel of [':where(.tablewrap) td strong', ':where(.tablewrap) td small', ':where(.tablewrap) td', '.mapnote']) {
        expect(contrast(tokenValue(TOKENS, theme, declOf(RULES, sel, 'color')), card), `${sel} ${theme}`).toBeGreaterThanOrEqual(4.5)
      }
    }
  })

  it('§7.1 MINOR-1 / MINOR-6 pairs from the shadcn tokens kept in styles.css until B5', () => {
    expect(contrast('#ffffff', shadcn('dark', '--destructive-solid'))).toBeGreaterThanOrEqual(4.5)
    const brand = shadcn('light', '--brand')
    for (const bg of [mix(brand, 0.05, '#ffffff'), mix(brand, 0.05, shadcn('light', '--canvas')), '#ffffff']) expect(contrast(shadcn('light', '--chip-info-fg'), bg)).toBeGreaterThanOrEqual(4.5)
    for (const bg of [mix(shadcn('dark', '--primary'), 0.05, shadcn('dark', '--card')), shadcn('dark', '--canvas')]) expect(contrast(shadcn('dark', '--chip-info-fg'), bg)).toBeGreaterThanOrEqual(4.5)
  })

  it('values defined in both places (styles.css shadcn token and design token) stay equal until B5 (§4.1)', () => {
    const same: [string, string, Theme[]][] = [
      ['--brand', '--ds-brand', ['light']], ['--primary', '--ds-brand', ['light', 'dark']], ['--ring', '--ds-focus-ring', ['dark']],
      ['--canvas', '--ds-canvas', THEMES], ['--card', '--ds-surface', THEMES], ['--heading', '--ds-text-heading', THEMES], ['--foreground', '--ds-text-body', THEMES],
      ['--subtle', '--ds-text-secondary', THEMES], ['--muted-foreground', '--ds-text-muted', THEMES], ['--border', '--ds-line', THEMES], ['--table-head', '--ds-table-head', THEMES],
      ['--chip-info-bg', '--ds-tone-info-bg', THEMES], ['--chip-info-fg', '--ds-tone-info-fg', THEMES], ['--chip-warning-bg', '--ds-tone-warning-bg', THEMES], ['--chip-warning-fg', '--ds-tone-warning-fg', THEMES],
    ]
    for (const [shadcnName, token, themes] of same) for (const theme of themes) expect(t(theme, token), `${shadcnName} ${theme}`).toBe(shadcn(theme, shadcnName))
  })
})

describe('ADMIN-DS-B1 §7.3 — literal location: colour, font-size and radius values only in the token definitions', () => {
  const COLOR_FUNCTION = /#[0-9a-f]{3,8}\b|\b(?:rgba?|hsla?|hwb|oklch|oklab|lab|lch|color|color-mix|light-dark)\(/i
  /** CSS 이름 색 전체(CSS Color 4 — transparent·currentColor는 색 값이 아니라 제외). */
  const NAMED_COLORS = new Set(('aliceblue antiquewhite aqua aquamarine azure beige bisque black blanchedalmond blue blueviolet brown burlywood cadetblue chartreuse chocolate coral ' +
    'cornflowerblue cornsilk crimson cyan darkblue darkcyan darkgoldenrod darkgray darkgreen darkgrey darkkhaki darkmagenta darkolivegreen darkorange darkorchid darkred darksalmon ' +
    'darkseagreen darkslateblue darkslategray darkslategrey darkturquoise darkviolet deeppink deepskyblue dimgray dimgrey dodgerblue firebrick floralwhite forestgreen fuchsia gainsboro ' +
    'ghostwhite gold goldenrod gray green greenyellow grey honeydew hotpink indianred indigo ivory khaki lavender lavenderblush lawngreen lemonchiffon lightblue lightcoral lightcyan ' +
    'lightgoldenrodyellow lightgray lightgreen lightgrey lightpink lightsalmon lightseagreen lightskyblue lightslategray lightslategrey lightsteelblue lightyellow lime limegreen linen ' +
    'magenta maroon mediumaquamarine mediumblue mediumorchid mediumpurple mediumseagreen mediumslateblue mediumspringgreen mediumturquoise mediumvioletred midnightblue mintcream ' +
    'mistyrose moccasin navajowhite navy oldlace olive olivedrab orange orangered orchid palegoldenrod palegreen paleturquoise palevioletred papayawhip peachpuff peru pink plum ' +
    'powderblue purple rebeccapurple red rosybrown royalblue saddlebrown salmon sandybrown seagreen seashell sienna silver skyblue slateblue slategray slategrey snow springgreen ' +
    'steelblue tan teal thistle tomato turquoise violet wheat white whitesmoke yellow yellowgreen').split(' '))
  /** 값 안의 색 리터럴: 함수·hex, 또는 var(…)·따옴표 글자를 뺀 낱말 중 이름 색. */
  const hasColorLiteral = (value: string) => COLOR_FUNCTION.test(value)
    || value.replace(/var\([^)]*\)/g, ' ').replace(/'[^']*'|"[^"]*"/g, ' ').split(/[^a-z-]+/i).some((w) => NAMED_COLORS.has(w.toLowerCase()))
  it('the token definitions come first and hold the only literal colours', () => {
    expect(isTokenBlock(RULES[0]!) && RULES[0]!.selectors[0] === ':root').toBe(true)
    expect(isTokenBlock(RULES[1]!) && RULES[1]!.selectors[0] === ':root[data-theme=dark]').toBe(true)
    expect(RULES.filter(isTokenBlock)).toHaveLength(2)
    const offenders = COMPONENT_RULES.flatMap((r) => Object.entries(r.decls).filter(([, v]) => hasColorLiteral(v)).map(([p, v]) => `${r.selectors.join(',')} { ${p}:${v} }`))
    expect(offenders).toEqual([])
  })

  it('font sizes and radii use the scale tokens; component rules define no custom properties', () => {
    const offenders = COMPONENT_RULES.flatMap((r) => Object.entries(r.decls)
      .filter(([p, v]) => ((p === 'font-size' || p === 'font' || p.endsWith('radius')) && /\d+(?:\.\d+)?px/.test(v)) || p.startsWith('--'))
      .map(([p, v]) => `${r.selectors.join(',')} { ${p}:${v} }`))
    expect(offenders).toEqual([])
  })

  it('every var() in the rules names a defined design token', () => {
    const used = new Set(COMPONENT_RULES.flatMap((r) => Object.values(r.decls).flatMap((v) => [...v.matchAll(/var\((--[\w-]+)/g)].map((m) => m[1]!))))
    expect([...used].filter((n) => !(n in TOKENS.light))).toEqual([])
    expect(Object.keys(TOKENS.light).filter((n) => !used.has(n))).toEqual([])
  })
})

describe('ADMIN-DS-B1 §7.3 — dark selector: dark theme is the token override, other dark rules are commented exceptions', () => {
  /** 이유 주석이 달린 예외(§4.1). 늘리려면 이 목록과 ADMIN-DS-001 §11을 함께 고치고 리뷰를 받는다. */
  const DARK_EXCEPTIONS = [':where(:root[data-theme=dark]) .btn']
  const darkRules = (rules: CssRule[]) => rules.filter((r) => r.selectors.some((s) => s.includes('data-theme')))

  it('only the token override block and the listed exceptions mention data-theme; exceptions carry a reason comment and use tokens only', () => {
    const exceptions = darkRules(COMPONENT_RULES)
    expect(exceptions.flatMap((r) => r.selectors)).toEqual(DARK_EXCEPTIONS)
    for (const r of exceptions) {
      expect(r.commented, r.selectors.join(',')).toBe(true)
      for (const v of Object.values(r.decls)) expect(v).toMatch(/^var\(--ds-[\w-]+\)$/)
    }
    expect(darkRules(RULES.filter(isTokenBlock)).map((r) => r.selectors[0])).toEqual([':root[data-theme=dark]'])
  })

  it('the theme switches only through data-theme (no prefers-color-scheme in the design system CSS)', () => {
    expect(CSS).not.toMatch(/prefers-color-scheme/)
  })
})

describe('ADMIN-DS-B1 §7.3 — focus ring (SC 1.4.11): every focus outline is 3px solid in the focus-ring token', () => {
  /** @media 밖에서 그 선택자를 가진 규칙을 파일 순서로 접어 최종 outline 굵기·모양·색(같은 명시도는 뒤 규칙이 이긴다). */
  const finalOutline = (sel: string) => {
    const out: { width?: string; style?: string; color?: string; shorthand: string[] } = { shorthand: [] }
    for (const r of COMPONENT_RULES) {
      if (r.media !== null || !r.selectors.includes(sel)) continue
      for (const [p, v] of Object.entries(r.decls)) {
        if (p === 'outline') {
          out.shorthand.push(v)
          const [width, style, ...color] = v.split(/\s+/)
          Object.assign(out, { width, style, color: color.join(' ') })
        }
        if (p === 'outline-width') out.width = v
        if (p === 'outline-style') out.style = v
        if (p === 'outline-color') out.color = v
      }
    }
    return out
  }

  it('buttons, links, summary and focusable headings: a 3px solid shorthand exists and the final width, style and colour are 3px solid var(--ds-focus-ring)', () => {
    for (const sel of ['button:focus-visible', 'a:focus-visible', 'summary:focus-visible', 'h2[tabindex="-1"]:focus-visible']) {
      const o = finalOutline(sel)
      expect(o.shorthand, sel).toContain('3px solid var(--ds-focus-ring)')
      expect([o.width, o.style, o.color], sel).toEqual(['3px', 'solid', 'var(--ds-focus-ring)'])
    }
  })

  it('form controls inside the reference containers: 2px solid var(--ds-focus-ring)', () => {
    for (const sel of [':where(.filters,.formrow,.modal,.rolebar,.matrix) input:focus', ':where(.filters,.formrow,.modal,.rolebar,.matrix) select:focus']) {
      const o = finalOutline(sel)
      expect([o.width, o.style, o.color], sel).toEqual(['2px', 'solid', 'var(--ds-focus-ring)'])
    }
  })

  // MERCHANT-IA-001 B6(검사 추가만): 목록 필터 묶음(.filterpanel) 입력·선택도 같은 초점선.
  it('form controls inside the list filter panel (B6 .filtercontrol): 2px solid var(--ds-focus-ring)', () => {
    for (const sel of ['.filtercontrol input:focus', '.filtercontrol select:focus']) {
      const o = finalOutline(sel)
      expect([o.width, o.style, o.color], sel).toEqual(['2px', 'solid', 'var(--ds-focus-ring)'])
    }
  })
})

describe('ADMIN-DS-B1 §7.3 — asset inlining stays off (CSP: no data: URIs in the bundle)', () => {
  it('vite.config keeps assetsInlineLimit: 0', () => {
    const config = readCss('vite.config.ts')
    expect([...config.matchAll(/assetsInlineLimit:\s*([^,\n}]+)/g)].map((m) => m[1]!.trim())).toEqual(['0'])
  })
})
