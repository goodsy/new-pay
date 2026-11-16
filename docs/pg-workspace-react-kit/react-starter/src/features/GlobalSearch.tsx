import { useEffect, useRef, useState } from 'react';
import { MENUS, pageTitle } from '../data/menus';
import { MODULE_IDS } from '../data/modules';
import { useWorkspace } from '../store/WorkspaceProvider';
import { DetailPanel } from './DetailPanel';

/** 전역 검색 모달(.modal): 메뉴명 + (검색어 있을 때) 전 모듈 행 전문검색, 최대 20건 */
export function GlobalSearch() {
  const { rows, go, openDrawer } = useWorkspace();
  const [q, setQ] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);
  useEffect(() => inputRef.current?.focus(), []);

  const results: { key: string; label: string; meta: string; onClick: () => void }[] = MENUS.filter((m) =>
    m.label.includes(q),
  ).map((m) => ({ key: 'menu-' + m.id, label: m.label, meta: '메뉴 →', onClick: () => go(m.id) }));

  if (q) {
    const lq = q.toLowerCase();
    MODULE_IDS.forEach((id) =>
      rows[id]
        .filter((r) => r.join(' ').toLowerCase().includes(lq))
        .forEach((r) =>
          results.push({
            key: id + r[1],
            label: r[0],
            meta: `${r[1]} · ${pageTitle(id)}`,
            onClick: () => openDrawer(<DetailPanel moduleId={id} rowKey={r[1]} />),
          }),
        ),
    );
  }

  return (
    <>
      <input ref={inputRef} id="globalq" placeholder="메뉴, 가맹점명 또는 TID 검색" value={q} onChange={(e) => setQ(e.target.value)} />
      <div id="globalresults">
        {results.length === 0 ? (
          <div className="empty">검색 결과가 없어요</div>
        ) : (
          results.slice(0, 20).map((r) => (
            <button type="button" key={r.key} className="result" onClick={r.onClick}>
              {r.label} <span className="subtle">{r.meta}</span>
            </button>
          ))
        )}
      </div>
    </>
  );
}
