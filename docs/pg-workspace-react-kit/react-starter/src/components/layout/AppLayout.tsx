import { useEffect, useRef, type ReactNode } from 'react';
import type { PageId } from '../../data/types';
import { GlobalSearch } from '../../features/GlobalSearch';
import { useWorkspace } from '../../store/WorkspaceProvider';
import { Header } from './Header';
import { Sidebar } from './Sidebar';

/**
 * 전체 셸: aside(고정) + .shell(header / main / footer) + overlay + toast
 *
 *  ┌────────┬───────────────────────────────────────┐
 *  │ aside  │ header 76px (crumb · search · tag)    │
 *  │ 236px  ├───────────────────────────────────────┤
 *  │ fixed  │ main  max-width 1500, padding 36/38   │
 *  │        │   .heading → (banner) → content       │
 *  │        ├───────────────────────────────────────┤
 *  │        │ footer                                │
 *  └────────┴───────────────────────────────────────┘
 */
export function AppLayout({ current, children }: { current: PageId; children: ReactNode }) {
  const { openModal, closeOverlay } = useWorkspace();

  // 전역 단축키: ESC 닫기, ⌘K / Ctrl+K 검색
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') closeOverlay();
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault();
        openModal(<GlobalSearch />);
      }
    };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [openModal, closeOverlay]);

  return (
    <>
      <Sidebar current={current} />
      <div className="shell">
        <Header current={current} />
        <main>{children}</main>
        <footer>PG WORKSPACE　　화면 설계용 샘플 데이터 · 실제 결제 및 송금이 실행되지 않습니다</footer>
      </div>
      <OverlayHost />
      <Toast />
    </>
  );
}

/** .overlay > .drawer(우측 550px) | .modal(중앙 650px). 배경 클릭 시 닫힘, 열릴 때 닫기 버튼에 포커스 */
function OverlayHost() {
  const { overlay, closeOverlay } = useWorkspace();
  const closeRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (overlay?.variant === 'drawer') closeRef.current?.focus();
  }, [overlay]);

  if (!overlay) return null;
  return (
    <div id="overlay" className="overlay" onClick={(e) => e.target === e.currentTarget && closeOverlay()}>
      <section className={overlay.variant} role="dialog" aria-modal="true">
        <button ref={closeRef} type="button" className="close" aria-label="닫기" onClick={closeOverlay}>
          ×
        </button>
        {overlay.content}
      </section>
    </div>
  );
}

function Toast() {
  const { toastMessage } = useWorkspace();
  return (
    <div id="toast" role="status" className={toastMessage ? 'show' : ''}>
      {toastMessage}
    </div>
  );
}
