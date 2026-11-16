import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 산출물은 Gradle processResources가 static/으로 복사한다(paycore.frontend). inline script·inline style을 만들지 않는다(CSP 'self').
// Tailwind(ADMIN-UI-001 shadcn/ui)는 빌드 때 정적 assets/*.css로 나온다(<link rel="stylesheet"> — 동일 출처).
//
// UI 참조 재현(ui-reference.html, src/uiref/**)은 PC 로컬 미리보기 전용이다(사용자 결정 2026-11-15). 기본 빌드(Gradle frontendBuild →
// 관리자 jar static/)에는 넣지 않고, `--mode uiref`일 때만 별도 출력(../build/uiref-dist)으로 빌드한다 — npm run build:uiref / preview:uiref.
// uiref는 PG Workspace React kit(docs/pg-workspace-react-kit) 이식본이다 — 원본 CSS(pg-workspace.css)만 쓰므로 Tailwind plugin을 넣지 않는다.
export default defineConfig(({ mode }) => {
  const uiref = mode === 'uiref'
  const input: Record<string, string> = uiref ? { uiref: 'ui-reference.html' } : { index: 'index.html' }
  return {
    plugins: uiref ? [react()] : [react(), tailwindcss()],
    base: '/',
    build: {
      outDir: uiref ? '../build/uiref-dist' : '../build/frontend-dist',
      emptyOutDir: true,
      assetsDir: 'assets',
      assetsInlineLimit: 0,
      modulePreload: { polyfill: false },
      sourcemap: false,
      rolldownOptions: {
        // 기본: 기존 산출물 이름(assets/index-*.js·css) 유지. uiref: Tailwind styles.css 없이 기준 CSS만 쓰는 재현 entry 하나.
        input,
      },
    },
    preview: uiref ? { open: '/ui-reference.html' } : {},
  }
})
