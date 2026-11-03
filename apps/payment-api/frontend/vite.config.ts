import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 결제창 번들(CHECKOUT-REACT-001 §6). Gradle processResources가 산출물을 static/으로 복사하고(paycore.frontend), payment-api가 index.html만
// checkout-shell/index.html로 옮긴다(정적 서빙 밖 — Controller가 데이터를 넣어 응답). inline script·inline style·원격 자원을 만들지 않는다(CSP 'self').
// 파일 이름은 고정(checkout.js·checkout.css): 결제창 응답은 Cache-Control: no-store라 hash가 필요 없고, 시험이 경로를 고정해 확인한다.
export default defineConfig({
  plugins: [react()],
  base: '/',
  build: {
    outDir: '../build/frontend-dist',
    emptyOutDir: true,
    assetsDir: 'checkout/assets',
    assetsInlineLimit: 0,
    cssCodeSplit: false,
    modulePreload: { polyfill: false },
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: 'checkout/assets/checkout.js',
        chunkFileNames: 'checkout/assets/checkout-[name].js',
        assetFileNames: (asset) =>
          asset.names.some((name) => name.endsWith('.css')) ? 'checkout/assets/checkout.css' : 'checkout/assets/[name][extname]',
      },
    },
  },
})
