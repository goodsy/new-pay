import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router-dom';
import App from './App';
import { WorkspaceProvider } from './store/WorkspaceProvider';
// 순서 중요: 원본 스타일 → 보정 스타일
import './styles/pg-workspace.css';
import './styles/overrides.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <WorkspaceProvider>
        <App />
      </WorkspaceProvider>
    </BrowserRouter>
  </StrictMode>,
);
