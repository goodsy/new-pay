import { afterEach, describe, expect, it, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { readFileSync } from 'node:fs'
import { App } from '../App'
import { fakeApi } from '../test/fakeApi'
import { applyStoredTheme, resolveTheme, THEME_STORAGE_KEY } from './theme'

/** D-ADMIN-HOME-001: 라이트(참고 시안) + 다크. OS 설정이 기본, 사이드바 아래 선택은 저장소에 남는다(직접 고를 때만). */

function stubMatchMedia(dark: boolean) {
  const listeners: ((e: { matches: boolean }) => void)[] = []
  const list = { matches: dark, media: '(prefers-color-scheme: dark)', addEventListener: (_: string, l: (e: { matches: boolean }) => void) => listeners.push(l), removeEventListener: vi.fn() }
  vi.stubGlobal('matchMedia', vi.fn(() => list))
  return (next: boolean) => {
    list.matches = next
    listeners.forEach((l) => l({ matches: next }))
  }
}

function signedIn() {
  const api = fakeApi({
    me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '관리자', roles: ['VIEWER'] })),
    transactionSearch: vi.fn(async () => ({ items: [], nextCursor: null, merchantLabels: 'OK' as const })),
  })
  return render(<App api={api} />)
}

afterEach(() => {
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  localStorage.clear()
  document.documentElement.removeAttribute('data-theme')
})

describe('theme (D-ADMIN-HOME-001 — light + dark)', () => {
  it('follows the OS setting by default (and its changes) without writing storage', async () => {
    const change = stubMatchMedia(true)
    signedIn()
    await screen.findByRole('navigation', { name: '메뉴' })
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    change(false)
    await vi.waitFor(() => expect(document.documentElement).toHaveAttribute('data-theme', 'light'))
    expect(localStorage.length).toBe(0)
    expect(within(screen.getByRole('group', { name: '화면 테마' })).getByRole('button', { name: '시스템' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('the sidebar toggle applies data-theme and persists the choice; 시스템 removes it; a stored choice is used on the next start', async () => {
    stubMatchMedia(false)
    const view = signedIn()
    const toggle = within(await screen.findByRole('group', { name: '화면 테마' }))
    expect(screen.getByRole('complementary').querySelector('.bottom')).toContainElement(toggle.getByRole('button', { name: '다크' }))
    await userEvent.click(toggle.getByRole('button', { name: '다크' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('dark')
    expect(toggle.getByRole('button', { name: '다크' })).toHaveAttribute('aria-pressed', 'true')
    view.unmount()
    signedIn()
    await screen.findByRole('navigation', { name: '메뉴' })
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    await userEvent.click(within(screen.getByRole('group', { name: '화면 테마' })).getByRole('button', { name: '라이트' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe('light')
    await userEvent.click(within(screen.getByRole('group', { name: '화면 테마' })).getByRole('button', { name: '시스템' }))
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
  })

  it('works when storage throws (private mode) and when matchMedia is missing; never writes inline styles', async () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    signedIn()
    await userEvent.click(within(await screen.findByRole('group', { name: '화면 테마' })).getByRole('button', { name: '다크' }))
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    expect(document.querySelectorAll('[style]')).toHaveLength(0)
    expect(document.querySelectorAll('style')).toHaveLength(0)
  })

  it('resolveTheme: explicit choice wins, system follows the OS', () => {
    expect(resolveTheme('system', true)).toBe('dark')
    expect(resolveTheme('system', false)).toBe('light')
    expect(resolveTheme('light', true)).toBe('light')
    expect(resolveTheme('dark', false)).toBe('dark')
  })

  it('review MINOR-3 (theme flash): applyStoredTheme sets <html data-theme> from the stored choice / OS before React renders; main.tsx calls it before createRoot (no inline script)', () => {
    stubMatchMedia(true)
    applyStoredTheme()
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    localStorage.setItem(THEME_STORAGE_KEY, 'light')
    applyStoredTheme()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    vi.unstubAllGlobals()
    vi.stubGlobal('matchMedia', undefined)
    localStorage.clear()
    applyStoredTheme()
    expect(document.documentElement).toHaveAttribute('data-theme', 'light')
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    stubMatchMedia(true)
    expect(() => applyStoredTheme()).not.toThrow()
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark')
    const main = readFileSync('src/main.tsx', 'utf8')
    const call = main.indexOf('applyStoredTheme()')
    expect(call).toBeGreaterThan(0)
    expect(call).toBeLessThan(main.indexOf('createRoot('))
    expect(readFileSync('index.html', 'utf8')).not.toMatch(/<script(?![^>]*\bsrc=)[^>]*>\s*\S/)
  })

  it('dark colours live only under data-theme=dark: styles.css tokens and the workspace.css dark block (reference blocks unchanged)', () => {
    const styles = readFileSync('src/styles.css', 'utf8')
    const workspace = readFileSync('src/workspace.css', 'utf8')
    expect(styles).toMatch(/:root\[data-theme="dark"\] \{[^}]*--card: #111a2b/)
    expect(styles).not.toMatch(/prefers-color-scheme/)
    expect(workspace).toMatch(/:root\[data-theme=dark\] body\{background:#0b1220;color:#dbe4f0\}/)
    const darkBlock = workspace.slice(workspace.indexOf(':root[data-theme=dark]{'))
    for (const line of darkBlock.split('\n').filter((l) => l.includes('{') && !l.startsWith('@') && !l.startsWith('/*') && !l.startsWith(' *'))) {
      expect(line.startsWith(':root[data-theme=dark]'), line).toBe(true)
    }
  })
})

/** WCAG 2.x 상대 휘도·대비(sRGB #rrggbb). 반투명 tint는 바탕 위에 섞은 색으로 잰다. */
const rgb = (hex: string) => [1, 3, 5].map((i) => parseInt(hex.slice(i, i + 2), 16)) as [number, number, number]
const lum = ([r, g, b]: number[]) => {
  const c = [r!, g!, b!].map((v) => {
    const s = v / 255
    return s <= 0.03928 ? s / 12.92 : ((s + 0.055) / 1.055) ** 2.4
  })
  return 0.2126 * c[0]! + 0.7152 * c[1]! + 0.0722 * c[2]!
}
const mix = (fg: string, alpha: number, bg: string) => rgb(fg).map((v, i) => Math.round(v * alpha + rgb(bg)[i]! * (1 - alpha)))
const ratio = (a: number[], b: number[]) => {
  const [x, y] = [lum(a), lum(b)].sort((p, q) => q - p) as [number, number]
  return (x + 0.05) / (y + 0.05)
}
const STYLES = readFileSync('src/styles.css', 'utf8')
const WORKSPACE = readFileSync('src/workspace.css', 'utf8')
const LIGHT_TOKENS = STYLES.slice(STYLES.indexOf('--radius: 0.75rem'), STYLES.indexOf(':root[data-theme="dark"] {'))
const DARK_TOKENS = STYLES.slice(STYLES.indexOf(':root[data-theme="dark"] {'), STYLES.indexOf('@theme inline'))
const BRAND_TOKENS = STYLES.slice(0, STYLES.indexOf('--radius: 0.75rem'))
const token = (block: string, name: string) => {
  const m = block.match(new RegExp(`${name}: (#[0-9a-f]{6});`))
  if (!m) throw new Error(`token ${name} missing`)
  return m[1]!
}

describe('independent review — contrast fixes (WCAG AA 4.5:1 for text)', () => {
  it('MINOR-1: dark destructive buttons use a dark-only solid red with white text (>= 4.5:1); light mode keeps --destructive; text-destructive (dark) is unchanged', () => {
    expect(DARK_TOKENS).toMatch(/--destructive: #f87171;/)
    const solid = token(DARK_TOKENS, '--destructive-solid')
    expect(ratio(rgb('#ffffff'), rgb(solid))).toBeGreaterThanOrEqual(4.5)
    expect(LIGHT_TOKENS).toMatch(/--destructive-solid: var\(--destructive\);/)
    expect(STYLES).toMatch(/--color-destructive-solid: var\(--destructive-solid\);/)
    const button = readFileSync('src/components/ui/button.tsx', 'utf8')
    expect(button).toMatch(/destructive: 'bg-destructive-solid text-white/)
    expect(button).not.toMatch(/destructive: 'bg-destructive /)
  })

  it('MINOR-2: .detailarea h3 small has a dark override readable on the dark card (>= 4.5:1)', () => {
    const m = WORKSPACE.match(/:root\[data-theme=dark\] \.detailarea h3 small\{color:(#[0-9a-f]{6})\}/)
    expect(m).not.toBeNull()
    expect(ratio(rgb(m![1]!), rgb('#111a2b'))).toBeGreaterThanOrEqual(4.5)
  })

  it('MINOR-6: brand text on the 5% brand tint uses the darker info token (>= 4.5:1 light and dark); the old text-primary pairing is gone', () => {
    const lightFg = token(LIGHT_TOKENS, '--chip-info-fg')
    const darkFg = token(DARK_TOKENS, '--chip-info-fg')
    const brand = token(BRAND_TOKENS, '--brand')
    // 현재 단계(bg-primary/5 tint)와 완료 단계(쪽 바탕 canvas·카드 흰 바탕) 모두.
    for (const bg of [mix(brand, 0.05, '#ffffff'), mix(brand, 0.05, token(LIGHT_TOKENS, '--canvas')), rgb('#ffffff')]) expect(ratio(rgb(lightFg), bg)).toBeGreaterThanOrEqual(4.5)
    for (const bg of [mix(token(DARK_TOKENS, '--primary'), 0.05, token(DARK_TOKENS, '--card')), rgb(token(DARK_TOKENS, '--canvas'))]) expect(ratio(rgb(darkFg), bg)).toBeGreaterThanOrEqual(4.5)
    const registration = readFileSync('src/screens/MerchantRegistrationScreen.tsx', 'utf8')
    expect(registration).toMatch(/current && 'border-primary bg-primary\/5 font-bold text-chip-info-fg hover:bg-primary\/5'/)
    const paymentOps = readFileSync('src/screens/PaymentOpsScreen.tsx', 'utf8')
    expect(paymentOps).toMatch(/done && 'border-primary text-chip-info-fg'/)
  })

  it('NIT: long KPI values wrap inside the stat card instead of overflowing', () => {
    expect(WORKSPACE).toMatch(/\.stat strong\{[^}]*overflow-wrap:anywhere/)
  })
})

/**
 * workspace.css에서 선택자 하나의 실제 글자색(같은 선택자는 뒤 규칙이 이긴다). 라이트 = 다크 블록 앞, 다크 = `:root[data-theme=dark]` 블록(더 구체적이라 라이트를 덮는다).
 * 선택자는 파일에 적힌 그대로(쉼표 목록 안 한 항목) 찾는다 — 없으면 실패(조용한 통과 없음).
 */
const DARK_START = WORKSPACE.indexOf(':root[data-theme=dark]{')
const cssColor = (block: string, selector: string, prop = 'color') => {
  let found: string | null = null
  for (const m of block.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/([^{}]+)\{([^{}]*)\}/g)) {
    const selectors = m[1]!.split(',').map((s) => s.trim())
    const value = m[2]!.match(new RegExp(`(?:^|;)${prop}:#([0-9a-f]{6}|[0-9a-f]{3})(?![0-9a-f])`))
    if (selectors.includes(selector) && value) found = value[1]!
  }
  if (found === null) throw new Error(`${prop} for ${selector} missing`)
  return `#${found.length === 3 ? [...found].map((c) => c + c).join('') : found}`
}

describe('TXN-LIST-ENRICH-001 review NIT-5 — the new list cells meet WCAG AA (4.5:1) in light and dark', () => {
  const row = (merchant: { name: string; code: string } | null) => ({
    paymentId: 'pay_01JC0000000000000000000001', environment: 'LIVE', merchantAccountId: 'mid_01JC0000000000000000000001', merchantOrderId: 'order-77',
    orderNamePreview: '데모 주문', orderNameTruncated: false, amountMinor: 12000, currency: 'KRW', paymentMethod: 'CARD', status: 'AUTHORIZED',
    createdAt: new Date().toISOString(), merchant,
  })
  const renderRecent = (labels: 'OK' | 'UNAVAILABLE') => {
    stubMatchMedia(false)
    const api = fakeApi({
      me: vi.fn(async () => ({ actorId: 'adm_1', displayName: '관리자', roles: ['VIEWER'] })),
      transactionSearch: vi.fn(async () => ({ items: [row(labels === 'OK' ? { name: '데모상점', code: 'demoshop01' } : null)], nextCursor: null, merchantLabels: labels })),
    })
    return render(<App api={api} />)
  }

  it('the merchant cell renders as .card .tablewrap td strong / td small (label OK) and the UNAVAILABLE note as .card p.mapnote', async () => {
    const view = renderRecent('OK')
    const recent = await screen.findByRole('region', { name: '최근 거래' })
    const cell = (await within(recent).findAllByRole('cell')).find((c) => c.textContent?.includes('데모상점 · demoshop01'))!
    expect(cell.closest('.card .tablewrap')).not.toBeNull()
    expect(cell.querySelector('strong')).toHaveTextContent('데모상점 · demoshop01')
    expect(cell.querySelector('small')).toHaveTextContent('LIVE · mid_01JC0000000000000000000001')
    view.unmount()
    renderRecent('UNAVAILABLE')
    const note = await within(await screen.findByRole('region', { name: '최근 거래' })).findByRole('note')
    expect(note.tagName).toBe('P')
    expect(note).toHaveClass('mapnote')
    expect(note.closest('.card')).not.toBeNull()
  })

  it('light: td strong, td small, plain td (MID-only / 결제수단) and .mapnote on the white card >= 4.5:1', () => {
    const light = WORKSPACE.slice(0, DARK_START)
    const card = cssColor(light, '.card', 'background')
    expect(card).toBe('#ffffff')
    for (const sel of [':where(.tablewrap) td strong', ':where(.tablewrap) td small', ':where(.tablewrap) td', '.mapnote']) {
      expect(ratio(rgb(cssColor(light, sel)), rgb(card)), sel).toBeGreaterThanOrEqual(4.5)
    }
  })

  it('dark: the same cells on the dark card >= 4.5:1', () => {
    const dark = WORKSPACE.slice(DARK_START)
    const card = cssColor(dark, ':root[data-theme=dark] .card', 'background')
    for (const sel of [':root[data-theme=dark] .tablewrap td strong', ':root[data-theme=dark] .tablewrap td small', ':root[data-theme=dark] .tablewrap td', ':root[data-theme=dark] .mapnote']) {
      expect(ratio(rgb(cssColor(dark, sel)), rgb(card)), sel).toBeGreaterThanOrEqual(4.5)
    }
  })
})
