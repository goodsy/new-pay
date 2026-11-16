import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { UiRefApp } from './UiRefApp'
import legacyUrl from './data/legacy.json?url'
// 기준 CSS 전체(원본 선언 순서 그대로, 끝의 Readable operations typography 포함). 관리자 앱의 Tailwind styles.css는 가져오지 않는다.
import './style.css'
import './uiref-csp.css'

const root = document.getElementById('root')
if (!root) throw new Error('#root element is missing')
createRoot(root).render(
  <StrictMode>
    <UiRefApp legacyUrl={legacyUrl} />
  </StrictMode>,
)
