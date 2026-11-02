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
    // ADMIN-UI-001: jsdom 시험 파일을 코어 수만큼 동시에 돌리면 CPU 경쟁으로 첫 시험이 5초 timeout·findBy 1초 대기에 걸렸다(실측 약 5회 중 1회).
    // timeout·기대값은 그대로 두고 동시 worker만 코어의 절반으로 줄인다(8코어 WSL 실측: worker 4개로 12회 연속 통과 — 명령 인자 6회 + 이 설정 6회,
    // 가장 긴 시험 약 2초, 전체 시간 약 20초. ADMIN-UI-001-SHADCN.md §8과 같은 수치).
    maxWorkers: '50%',
    reporters: ['default', 'junit'],
    outputFile: { junit: '../build/test-results/frontend/TEST-vitest.xml' },
  },
})
