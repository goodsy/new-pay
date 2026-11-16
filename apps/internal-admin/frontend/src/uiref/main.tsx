import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { App } from './App'
import { WorkspaceProvider } from './store/WorkspaceProvider'
// 순서 중요: 원본 스타일(kit pg-workspace.css, 수정 금지) → 보정 스타일. 관리자 앱의 Tailwind styles.css·workspace.css 는 가져오지 않는다.
import './styles/pg-workspace.css'
import './styles/overrides.css'

// UI 참조 미리보기(PC 로컬 전용, vite --mode uiref). 샘플 데이터만 쓰고 API 요청·실제 결제/지급을 하지 않는다.
const root = document.getElementById('root')
if (!root) throw new Error('#root element is missing')
createRoot(root).render(
  <StrictMode>
    <WorkspaceProvider>
      <App />
    </WorkspaceProvider>
  </StrictMode>,
)
