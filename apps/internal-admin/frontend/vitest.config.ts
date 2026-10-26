import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// allowOnly:false — .only가 남으면 실패, passWithNoTests:false — 시험 0건은 실패(D-36).
// it.todo·it.skip은 JUnit skipped로 집계되어 TEST-SUMMARY가 FAIL로 판정한다(S0 §17.4, Q-S0-3).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    allowOnly: false,
    passWithNoTests: false,
    reporters: ['default', 'junit'],
    outputFile: { junit: '../build/test-results/frontend/TEST-vitest.xml' },
  },
})
