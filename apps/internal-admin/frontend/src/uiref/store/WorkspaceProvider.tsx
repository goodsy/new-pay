import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { MODULES } from '../data/modules'
import type { ModuleId, PageId, Row } from '../data/types'
import { useHashRoute } from './useHashRoute'

/**
 * 화면 전역 상태를 한곳에 둔다 (원본의 전역 변수 + #overlay + #toast 대응)
 * 1) route   : 현재 페이지(해시) + 이동 순번 — go() 로만 이동
 * 2) rows    : 모듈별 목록 데이터 (데모 등록 시 앞에 추가, 새로고침하면 초기화 — 원본과 동일). 서버 요청 없음
 * 3) overlay : 우측 Drawer(.drawer) / 중앙 Modal(.modal) 단일 슬롯 — 동시에 1개만 열린다
 * 4) toast   : 하단 중앙 토스트 2.8초
 */
type OverlayVariant = 'drawer' | 'modal'
export interface OverlayState {
  variant: OverlayVariant
  content: ReactNode
  /** 열 때마다 증가 — 같은 종류의 패널을 다시 열어도 새로 마운트한다(원본은 매번 innerHTML 을 새로 쓴다) */
  key: number
}

interface WorkspaceCtx {
  page: PageId
  /** 이동 순번(go 호출마다 증가) — ModulePage 재마운트 key 에 쓴다 */
  navSeq: number
  rows: Readonly<Record<ModuleId, readonly Row[]>>
  addRow: (id: ModuleId, row: Row) => void
  overlay: OverlayState | null
  openDrawer: (content: ReactNode) => void
  openModal: (content: ReactNode) => void
  closeOverlay: () => void
  toastMessage: string
  toastOpen: boolean
  toast: (msg: string) => void
  /** 원본 go(): 오버레이 닫기 + 페이지 이동(해시) + 스크롤 최상단 */
  go: (id: PageId) => void
}

const Ctx = createContext<WorkspaceCtx | null>(null)

const initialRows = (): Record<ModuleId, readonly Row[]> => ({
  merchant: MODULES.merchant.rows,
  source: MODULES.source.rows,
  payment: MODULES.payment.rows,
  acquire: MODULES.acquire.rows,
  settle: MODULES.settle.rows,
  risk: MODULES.risk.rows,
  report: MODULES.report.rows,
  ops: MODULES.ops.rows,
})

export const TOAST_MS = 2800

export function WorkspaceProvider({ children }: { children: ReactNode }) {
  const [route, navigate] = useHashRoute()
  const [rows, setRows] = useState(initialRows)
  const [overlay, setOverlay] = useState<OverlayState | null>(null)
  const [toastMessage, setToastMessage] = useState('')
  const [toastOpen, setToastOpen] = useState(false)
  const overlaySeq = useRef(0)
  const toastTimers = useRef(new Set<number>())

  const addRow = useCallback((id: ModuleId, row: Row) => {
    setRows((prev) => ({ ...prev, [id]: [row, ...prev[id]] }))
  }, [])

  const closeOverlay = useCallback(() => setOverlay(null), [])
  const openDrawer = useCallback((content: ReactNode) => setOverlay({ variant: 'drawer', content, key: ++overlaySeq.current }), [])
  const openModal = useCallback((content: ReactNode) => setOverlay({ variant: 'modal', content, key: ++overlaySeq.current }), [])

  // 원본 toast(): 호출마다 2.8초 timer 를 따로 건다(이전 timer 를 지우지 않음 — 앞선 timer 가 새 토스트를 일찍 닫는 것도 원본 그대로).
  const toast = useCallback((msg: string) => {
    setToastMessage(msg)
    setToastOpen(true)
    const id = window.setTimeout(() => {
      toastTimers.current.delete(id)
      setToastOpen(false)
    }, TOAST_MS)
    toastTimers.current.add(id)
  }, [])
  useEffect(() => {
    const timers = toastTimers.current
    return () => {
      timers.forEach((id) => window.clearTimeout(id))
      timers.clear()
    }
  }, [])

  const go = useCallback(
    (id: PageId) => {
      setOverlay(null)
      navigate(id)
      window.scrollTo(0, 0)
    },
    [navigate],
  )

  const value = useMemo<WorkspaceCtx>(
    () => ({
      page: route.page,
      navSeq: route.seq,
      rows,
      addRow,
      overlay,
      openDrawer,
      openModal,
      closeOverlay,
      toastMessage,
      toastOpen,
      toast,
      go,
    }),
    [route, rows, addRow, overlay, openDrawer, openModal, closeOverlay, toastMessage, toastOpen, toast, go],
  )
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useWorkspace(): WorkspaceCtx {
  const ctx = useContext(Ctx)
  if (!ctx) throw new Error('useWorkspace must be used within WorkspaceProvider')
  return ctx
}
