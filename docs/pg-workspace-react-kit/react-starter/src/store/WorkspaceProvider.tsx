import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react';
import { useNavigate } from 'react-router-dom';
import { MODULES } from '../data/modules';
import type { ModuleId, PageId, Row } from '../data/types';

/**
 * 화면 전역 상태 3종을 한곳에 둔다 (원본의 전역 변수 + #overlay + #toast 대응)
 * 1) rows    : 모듈별 목록 데이터 (데모 등록 시 unshift) → 실제 연동 시 React Query 등 서버 상태로 교체
 * 2) overlay : 우측 Drawer(.drawer) / 중앙 Modal(.modal) 단일 슬롯
 * 3) toast   : 하단 중앙 토스트 2.8초
 */
type OverlayVariant = 'drawer' | 'modal';
interface OverlayState {
  variant: OverlayVariant;
  content: ReactNode;
}

interface WorkspaceCtx {
  rows: Record<ModuleId, Row[]>;
  addRow: (id: ModuleId, row: Row) => void;
  overlay: OverlayState | null;
  openDrawer: (content: ReactNode) => void;
  openModal: (content: ReactNode) => void;
  closeOverlay: () => void;
  toastMessage: string | null;
  toast: (msg: string) => void;
  /** 원본 go(): 페이지 이동 + 오버레이 닫기 + 스크롤 최상단 */
  go: (id: PageId) => void;
}

const Ctx = createContext<WorkspaceCtx | null>(null);

const initialRows = () =>
  Object.fromEntries(Object.entries(MODULES).map(([k, v]) => [k, [...v.rows]])) as Record<ModuleId, Row[]>;

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate();
  const [rows, setRows] = useState(initialRows);
  const [overlay, setOverlay] = useState<OverlayState | null>(null);
  const [toastMessage, setToastMessage] = useState<string | null>(null);
  const toastTimer = useRef<number | undefined>(undefined);

  const addRow = useCallback((id: ModuleId, row: Row) => {
    setRows((prev) => ({ ...prev, [id]: [row, ...prev[id]] }));
  }, []);

  const closeOverlay = useCallback(() => setOverlay(null), []);
  const openDrawer = useCallback((content: ReactNode) => setOverlay({ variant: 'drawer', content }), []);
  const openModal = useCallback((content: ReactNode) => setOverlay({ variant: 'modal', content }), []);

  const toast = useCallback((msg: string) => {
    setToastMessage(msg);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToastMessage(null), 2800);
  }, []);

  const go = useCallback(
    (id: PageId) => {
      setOverlay(null);
      navigate(`/${id}`);
      window.scrollTo(0, 0);
    },
    [navigate],
  );

  const value = useMemo(
    () => ({ rows, addRow, overlay, openDrawer, openModal, closeOverlay, toastMessage, toast, go }),
    [rows, addRow, overlay, openDrawer, openModal, closeOverlay, toastMessage, toast, go],
  );
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useWorkspace(): WorkspaceCtx {
  const ctx = useContext(Ctx);
  if (!ctx) throw new Error('useWorkspace must be used within WorkspaceProvider');
  return ctx;
}
