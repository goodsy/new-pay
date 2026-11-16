import { AppLayout } from './components/layout/AppLayout'
import { isModuleId } from './data/modules'
import { AccessPage } from './pages/AccessPage'
import { HomePage } from './pages/HomePage'
import { MenuMapPage } from './pages/MenuMapPage'
import { ModulePage } from './pages/ModulePage'
import { useWorkspace } from './store/WorkspaceProvider'

/**
 * 라우팅: 원본 해시 그대로(#home | #merchant | #source | #payment | #acquire | #settle | #risk | #report | #ops | #access | #map).
 * kit 의 react-router-dom 대신 사내 해시 라우터(store/useHashRoute)를 쓴다 — 의존성 추가 없음.
 * ModulePage 는 이동마다(key = 페이지 + 이동 순번) 재마운트되어 탭·검색어·상태 필터가 초기화된다(원본 go()).
 */
export function App() {
  const { page, navSeq } = useWorkspace()

  let content
  if (page === 'home') content = <HomePage />
  else if (page === 'access') content = <AccessPage />
  else if (page === 'map') content = <MenuMapPage />
  else if (isModuleId(page)) content = <ModulePage key={`${page}:${navSeq}`} moduleId={page} />

  return <AppLayout current={page}>{content}</AppLayout>
}
