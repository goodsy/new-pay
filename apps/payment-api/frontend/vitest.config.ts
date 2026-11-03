import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'

// internal-admin과 같은 규칙: allowOnly:false(.only 남으면 실패), passWithNoTests:false(0건 실패), it.todo·it.skip은 JUnit skipped → TEST-SUMMARY FAIL.
// maxWorkers 50%(internal-admin ADMIN-UI-001 실측 — jsdom 동시 실행 CPU 경쟁으로 timeout을 피한다, timeout·기대값은 그대로).
export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    include: ['src/**/*.test.{ts,tsx}'],
    setupFiles: ['src/test/setup.ts'],
    allowOnly: false,
    passWithNoTests: false,
    maxWorkers: '50%',
    reporters: ['default', 'junit'],
    outputFile: { junit: '../build/test-results/frontend/TEST-vitest.xml' },
  },
})
