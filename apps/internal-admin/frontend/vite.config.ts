import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// 산출물은 Gradle processResources가 static/으로 복사한다(paycore.frontend). inline script·inline style을 만들지 않는다(CSP 'self').
// Tailwind(ADMIN-UI-001 shadcn/ui)는 빌드 때 정적 assets/*.css로 나온다(<link rel="stylesheet"> — 동일 출처).
export default defineConfig({
  plugins: [react(), tailwindcss()],
  base: '/',
  build: {
    outDir: '../build/frontend-dist',
    emptyOutDir: true,
    assetsDir: 'assets',
    assetsInlineLimit: 0,
    modulePreload: { polyfill: false },
    sourcemap: false,
  },
})
