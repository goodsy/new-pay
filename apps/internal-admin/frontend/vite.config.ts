import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 산출물은 Gradle processResources가 static/으로 복사한다(paycore.frontend). inline script·inline style을 만들지 않는다(CSP 'self').
export default defineConfig({
  plugins: [react()],
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
