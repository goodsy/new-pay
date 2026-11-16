import { Navigate, Route, Routes, useParams } from 'react-router-dom';
import { AppLayout } from './components/layout/AppLayout';
import { isPageId } from './data/menus';
import { isModuleId } from './data/modules';
import { AccessPage } from './pages/AccessPage';
import { HomePage } from './pages/HomePage';
import { MenuMapPage } from './pages/MenuMapPage';
import { ModulePage } from './pages/ModulePage';

/**
 * 라우팅: 원본 해시(#merchant) → 경로(/merchant) 1:1 매핑
 * /home | /merchant | /source | /payment | /acquire | /settle | /risk | /report | /ops | /access | /map
 */
export default function App() {
  return (
    <Routes>
      <Route path="/" element={<Navigate to="/home" replace />} />
      <Route path="/:pageId" element={<PageSwitch />} />
      <Route path="*" element={<Navigate to="/home" replace />} />
    </Routes>
  );
}

function PageSwitch() {
  const { pageId } = useParams();
  if (!isPageId(pageId)) return <Navigate to="/home" replace />;

  let page;
  if (pageId === 'home') page = <HomePage />;
  else if (pageId === 'access') page = <AccessPage />;
  else if (pageId === 'map') page = <MenuMapPage />;
  else if (isModuleId(pageId)) page = <ModulePage key={pageId} moduleId={pageId} />;

  return <AppLayout current={pageId}>{page}</AppLayout>;
}
