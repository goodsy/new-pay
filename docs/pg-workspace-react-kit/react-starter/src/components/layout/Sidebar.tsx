import { Fragment } from 'react';
import { Link } from 'react-router-dom';
import { MENUS, NAV_BADGES, NAV_GROUP_LABELS } from '../../data/menus';
import type { PageId } from '../../data/types';
import { useWorkspace } from '../../store/WorkspaceProvider';

/**
 * aside — 좌측 고정 사이드바 (236px / ≤1100px 205px / ≤720px 상단 가로 메뉴)
 * 구성: 브랜드 → 워크스페이스 선택 → nav(그룹 라벨 + 아이콘 메뉴) → 하단(메뉴 개편 지도, 사용자)
 * 원본 CSS 가 `aside`, `nav` 요소 선택자를 쓰므로 태그를 바꾸지 말 것.
 */
export function Sidebar({ current }: { current: PageId }) {
  const { go } = useWorkspace();
  return (
    <aside>
      <Link className="brand" to="/home">
        <i>p</i> pg workspace
      </Link>
      <div className="workspace">
        핑거페이 <small>내부 관리자</small> ⌄
      </div>
      <nav>
        {MENUS.map((m, i) => (
          <Fragment key={m.id}>
            {NAV_GROUP_LABELS[i] && <div className="label">{NAV_GROUP_LABELS[i]}</div>}
            <button
              type="button"
              className={current === m.id ? 'active' : ''}
              aria-current={current === m.id ? 'page' : undefined}
              onClick={() => go(m.id)}
            >
              <svg viewBox="0 0 24 24" aria-hidden="true">
                <path d={m.iconPath} />
              </svg>
              {m.label}
              {NAV_BADGES[m.id] !== undefined && <em>{NAV_BADGES[m.id]}</em>}
            </button>
          </Fragment>
        ))}
      </nav>
      <div className="bottom">
        <button type="button" onClick={() => go('map')}>
          ▦　메뉴 개편 지도
        </button>
        <hr />
        <b>김 운영</b>
        <small>운영 관리자 · 데모</small>
      </div>
    </aside>
  );
}
