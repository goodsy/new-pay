import { pageTitle } from '../../data/menus';
import type { PageId } from '../../data/types';
import { GlobalSearch } from '../../features/GlobalSearch';
import { useWorkspace } from '../../store/WorkspaceProvider';

/** header — 76px 상단 바: 브레드크럼(#crumb) · 전역 검색 버튼(⌘K) · 환경 태그 */
export function Header({ current }: { current: PageId }) {
  const { openModal } = useWorkspace();
  return (
    <header>
      <span id="crumb">워크스페이스　/　{pageTitle(current)}</span>
      <button type="button" className="search" onClick={() => openModal(<GlobalSearch />)}>
        ⌕　메뉴, 가맹점, TID 검색　 <kbd>⌘ K</kbd>
      </button>
      <span className="tag blue">프로토타입</span>
    </header>
  );
}
