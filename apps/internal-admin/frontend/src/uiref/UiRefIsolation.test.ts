import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'

// UI 참조 미리보기(src/uiref, ui-reference.html)는 PC 로컬 전용이다(사용자 결정 2026-11-15, plan A 2026-11-16).
// 기본 빌드(Gradle frontendBuild → 관리자 jar static/)와 운영 화면에 섞이면 안 된다. Vitest 작업 디렉터리 = frontend/.
const KIT = '../../../docs/pg-workspace-react-kit'
const PG_WORKSPACE_CSS_SHA256 = '36781c342fa04efe9d844a0a1c55b0349afb331b4d9837d19898d7b405a0c7a9'
const LEGACY_JSON_SHA256 = '3f086d3a051fcfa55fa37287a0c019e16a84fd315f7a0a71bf2f000101b1eab7'

async function sha256(text: string): Promise<string> {
  const digest = await globalThis.crypto.subtle.digest('SHA-256', new TextEncoder().encode(text))
  return [...new Uint8Array(digest)].map((b) => b.toString(16).padStart(2, '0')).join('')
}

// 프로젝트 src 전체 원문(vite import.meta.glob ?raw) — 경로는 '/src/...'
const SOURCES = import.meta.glob<string>('/src/**/*.{ts,tsx}', { query: '?raw', import: 'default', eager: true })
const stripComments = (code: string) => code.replace(/\/\*[\s\S]*?\*\//g, '').replace(/^\s*\/\/.*$/gm, '')
const importSpecs = (code: string) => [...code.matchAll(/(?:import|export)\s[^'"]*?from\s+['"]([^'"]+)['"]|import\s+['"]([^'"]+)['"]|import\(\s*['"]([^'"]+)['"]\s*\)/g)].map((m) => m[1] ?? m[2] ?? m[3] ?? '')

function resolveRelative(fromFile: string, spec: string): string {
  const parts = fromFile.split('/').slice(0, -1)
  for (const seg of spec.split('/')) {
    if (seg === '..') parts.pop()
    else if (seg !== '.') parts.push(seg)
  }
  return parts.join('/')
}

describe('kit 원본 파일 고정', () => {
  it('styles/pg-workspace.css is the kit react-starter copy, unmodified (sha256)', async () => {
    const ours = readFileSync('src/uiref/styles/pg-workspace.css', 'utf8')
    const kit = readFileSync(`${KIT}/react-starter/src/styles/pg-workspace.css`, 'utf8')
    expect(await sha256(ours)).toBe(PG_WORKSPACE_CSS_SHA256)
    expect(await sha256(kit)).toBe(PG_WORKSPACE_CSS_SHA256)
  })

  // 이전 미리보기의 data/legacy.json(docs/ui-reference 사본, 공백 포함 서식 22,541 bytes)은 kit 원본(20,232 bytes)과 바이트가 달랐다 — kit 원본으로 교체.
  it('data/legacy.json is the kit reference/original/legacy.json byte for byte (sha256) with 165 menus', async () => {
    const ours = readFileSync('src/uiref/data/legacy.json', 'utf8')
    expect(ours).toBe(readFileSync(`${KIT}/reference/original/legacy.json`, 'utf8'))
    expect(await sha256(ours)).toBe(LEGACY_JSON_SHA256)
    expect(JSON.parse(ours)).toHaveLength(165)
  })

  it('overrides.css only adds commented rules with original values (no new colors/sizes, imports or URLs)', () => {
    const css = readFileSync('src/uiref/styles/overrides.css', 'utf8')
    expect(css).not.toMatch(/@import|url\(/i)
    expect(css.replace(/\/\*[\s\S]*?\*\//g, '')).not.toMatch(/#[0-9a-f]{3,8}\b|rgba?\(|hsla?\(/i)
    expect([...css.replace(/\/\*[\s\S]*?\*\//g, '').matchAll(/(\d+(?:\.\d+)?)px/g)].map((m) => m[0]).sort()).toEqual(['28px', '30px'])
    const chunks = css.split('}').filter((c) => c.includes('{'))
    expect(chunks.length).toBeGreaterThan(0)
    for (const chunk of chunks) expect(chunk.slice(0, chunk.indexOf('{'))).toContain('*/') // 규칙마다 이유 주석
  })

  it('main.tsx imports pg-workspace.css then overrides.css and never the admin stylesheets or Tailwind', () => {
    const main = readFileSync('src/uiref/main.tsx', 'utf8')
    const a = main.indexOf("import './styles/pg-workspace.css'")
    expect(a).toBeGreaterThanOrEqual(0)
    expect(main.indexOf("import './styles/overrides.css'")).toBeGreaterThan(a)
    expect(importSpecs(main).filter((s) => !s.startsWith('./styles/') && !s.startsWith('./') && !/^react/.test(s))).toEqual([])
    expect(importSpecs(main).filter((s) => /\.css$/.test(s))).toEqual(['./styles/pg-workspace.css', './styles/overrides.css'])
    expect(stripComments(main)).not.toMatch(/tailwind|['"/]workspace\.css|['"/]styles\.css/)
  })
})

describe('미리보기 코드 격리', () => {
  const uirefFiles = Object.keys(SOURCES).filter((p) => p.startsWith('/src/uiref/'))

  it('uiref code imports only react/react-dom and files inside src/uiref (no admin code, no router dependency, no API client)', () => {
    expect(uirefFiles.length).toBeGreaterThan(20)
    for (const file of uirefFiles.filter((p) => !/\.test\.tsx?$/.test(p))) {
      for (const spec of importSpecs(SOURCES[file]!)) {
        if (spec.startsWith('.')) expect(resolveRelative(file, spec), `${file} → ${spec}`).toMatch(/^\/src\/uiref\//)
        else expect(spec, `${file} → ${spec}`).toMatch(/^react(-dom\/client)?$/)
      }
      expect(stripComments(SOURCES[file]!), file).not.toMatch(/\bfetch\(|XMLHttpRequest|react-router/)
    }
  })

  it('no production source outside src/uiref imports the preview', () => {
    const others = Object.keys(SOURCES).filter((p) => !p.startsWith('/src/uiref/'))
    expect(others.length).toBeGreaterThan(20)
    for (const file of others) for (const spec of importSpecs(SOURCES[file]!)) expect(spec, file).not.toMatch(/uiref/)
    expect(readFileSync('index.html', 'utf8')).not.toMatch(/uiref|ui-reference/)
    expect(readFileSync('ui-reference.html', 'utf8')).toContain('<script type="module" src="/src/uiref/main.tsx"></script>')
  })

  it('adds no dependency for the preview (in-house hash router instead of react-router-dom)', () => {
    const pkg = JSON.parse(readFileSync('package.json', 'utf8')) as { dependencies: Record<string, string>; devDependencies: Record<string, string> }
    const all = { ...pkg.dependencies, ...pkg.devDependencies }
    expect(Object.keys(all).filter((d) => /router|playwright|puppeteer|postcss-prefix|styled|emotion/.test(d))).toEqual([])
  })
})

describe('UI 재현 빌드 분리', () => {
  type Plugin = { name?: string } | Plugin[] | false | null | undefined
  type Built = { plugins: Plugin[]; build: { outDir: string; rolldownOptions: { input: Record<string, string> } } }
  async function configFor(mode: string): Promise<Built> {
    const { default: config } = await import('../../vite.config')
    const fn = config as unknown as (env: { command: 'build'; mode: string; isSsrBuild: boolean; isPreview: boolean }) => Built
    return fn({ command: 'build', mode, isSsrBuild: false, isPreview: false })
  }
  const pluginNames = (ps: Plugin[]): string[] => ps.flatMap((p) => (Array.isArray(p) ? pluginNames(p) : p && p.name ? [p.name] : []))

  it('builds only the admin index.html into frontend-dist by default (production mode)', async () => {
    const c = await configFor('production')
    expect(c.build.rolldownOptions.input).toEqual({ index: 'index.html' })
    expect(c.build.outDir).toBe('../build/frontend-dist')
    expect(pluginNames(c.plugins).some((n) => n.startsWith('@tailwindcss'))).toBe(true)
  })

  it('builds the preview only in uiref mode, into a separate uiref-dist, without the Tailwind plugin', async () => {
    const c = await configFor('uiref')
    expect(c.build.rolldownOptions.input).toEqual({ uiref: 'ui-reference.html' })
    expect(c.build.outDir).toBe('../build/uiref-dist')
    expect(pluginNames(c.plugins).some((n) => n.startsWith('@tailwindcss'))).toBe(false)
  })

  it('keeps Gradle frontendBuild on the default mode and copies only frontend-dist into static/', () => {
    const gradle = readFileSync('../../../build-logic/src/main/kotlin/paycore.frontend.gradle.kts', 'utf8')
    expect(gradle).toContain('runNode("node_modules/vite/bin/vite.js", "build")\n')
    expect(gradle).not.toMatch(/--mode|uiref-dist/)
    expect(gradle).toContain('from(frontendBuild) { into("static") }')
  })
})
