import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import './styles.css'
// ADMIN-UI-WORKSPACE-001: 참고 시안 CSS(cascade layer workspace — 순서는 styles.css가 정한다).
import './workspace.css'
import { applyStoredTheme } from './lib/theme'

// 리뷰 MINOR-3: React가 그리기 전에 테마를 맞춰 라이트 → 다크 깜빡임을 줄인다(inline script 없음 — CSP).
applyStoredTheme()

const root = document.getElementById('root')
if (!root) throw new Error('#root element is missing')
createRoot(root).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
