import { useCallback, useEffect, useMemo, useRef, useState, type MouseEvent } from 'react'
import { ALL_STATUS, conf, confIds, filterRows, isConfId, isPageId, type ConfId, type LegacyRow, type PageId, type Row } from './data'
import { UiContext, type LegacyState, type Overlay, type Ui, type WizardForm } from './context'
import { Footer, Header, Sidebar } from './components/Shell'
import { HomePage } from './pages/HomePage'
import { ModulePage } from './pages/ModulePage'
import { AccessPage } from './pages/AccessPage'
import { MenuMapPage } from './pages/MenuMapPage'
import { AccountsPanel, AuditPanel, DetailPanel, SearchModal, SourceFormPanel, WizardPanel } from './panels/Panels'

function initialPage(): PageId {
  const p = window.location.hash.slice(1)
  return isPageId(p) ? p : 'home'
}

function initialRows(): Record<ConfId, readonly Row[]> {
  return Object.fromEntries(confIds.map((id) => [id, conf[id].rows])) as Record<ConfId, readonly Row[]>
}

/**
 * UI 재현(ui-reference) — docs/ui-reference/reference(index.html·style.css·app.js)의 화면을 React로 옮긴 것.
 * 샘플 데이터만 쓰고 실제 결제·지급·채번·API 요청을 하지 않는다. 운영 관리자 앱(App.tsx)과 별개 entry다.
 */
export function UiRefApp({ legacyUrl }: { legacyUrl: string }) {
  const [page, setPage] = useState<PageId>(initialPage)
  const [tab, setTabState] = useState(0)
  const [query, setQuery] = useState('')
  const [status, setStatus] = useState(ALL_STATUS)
  const [rows, setRows] = useState<Record<ConfId, readonly Row[]>>(initialRows)
  const [legacy, setLegacy] = useState<LegacyState>({ state: 'loading' })
  const [overlay, setOverlay] = useState<Overlay | null>(null)
  const [searchQ, setSearchQ] = useState('')
  const [wiz, setWiz] = useState(0)
  const [form, setForm] = useState<WizardForm>({})
  const [role, setRole] = useState('운영 담당자')
  const [perms, setPerms] = useState<Record<string, boolean>>({})
  const [toastText, setToastText] = useState('')
  const [toastOpen, setToastOpen] = useState(false)
  const pageRef = useRef(page)
  pageRef.current = page

  const toastTimers = useRef(new Set<number>())
  const toast = useCallback((t: string) => {
    setToastText(t)
    setToastOpen(true)
    // 원본 app.js toast()와 같이 호출마다 2.8초 timer를 따로 건다(이전 timer가 새 toast를 일찍 닫을 수 있는 것도 원본 그대로).
    const id = window.setTimeout(() => {
      toastTimers.current.delete(id)
      setToastOpen(false)
    }, 2800)
    toastTimers.current.add(id)
  }, [])
  // unmount 시 아직 남은 toast timer를 모두 정리한다.
  useEffect(() => {
    const timers = toastTimers.current
    return () => {
      timers.forEach((id) => window.clearTimeout(id))
      timers.clear()
    }
  }, [])

  const closePanel = useCallback(() => setOverlay(null), [])

  /** app.js go(): 화면 이동 시 탭·조회조건 초기화, hash 동기화, 패널 닫기, 맨 위로 */
  const go = useCallback((id: PageId) => {
    setPage(id)
    pageRef.current = id
    setTabState(0)
    setQuery('')
    setStatus(ALL_STATUS)
    window.location.hash = id
    setOverlay(null)
    window.scrollTo(0, 0)
  }, [])

  // 원본 hashchange: 다른 유효 화면이면 이동(패널은 그대로).
  useEffect(() => {
    const onHash = () => {
      const p = window.location.hash.slice(1)
      if (p !== pageRef.current && isPageId(p)) {
        setPage(p)
        pageRef.current = p
        setTabState(0)
        setQuery('')
        setStatus(ALL_STATUS)
      }
    }
    window.addEventListener('hashchange', onHash)
    return () => window.removeEventListener('hashchange', onHash)
  }, [])

  // legacy.json — 같은 출처 정적 asset. 로딩·실패 상태를 둔다.
  useEffect(() => {
    let live = true
    fetch(legacyUrl)
      .then((r) => {
        if (!r.ok) throw new Error('HTTP ' + r.status)
        return r.json() as Promise<LegacyRow[]>
      })
      .then((data) => {
        if (live) setLegacy(Array.isArray(data) ? { state: 'ok', rows: data } : { state: 'error' })
      })
      .catch(() => {
        if (live) setLegacy({ state: 'error' })
      })
    return () => {
      live = false
    }
  }, [legacyUrl])

  const openPanel = useCallback((o: Overlay) => {
    if (o.kind === 'search') setSearchQ('')
    setOverlay(o)
  }, [])

  // Escape 닫기, ⌘/Ctrl+K 검색
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setOverlay(null)
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        openPanel({ kind: 'search' })
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [openPanel])

  // 패널을 열 때(마법사 단계 이동 포함) 원본처럼 닫기 버튼 또는 검색 입력에 초점을 둔다.
  const overlayEl = useRef<HTMLDivElement>(null)
  useEffect(() => {
    if (!overlay) return
    const target = overlay.kind === 'search' ? overlayEl.current?.querySelector<HTMLElement>('#globalq') : overlayEl.current?.querySelector<HTMLElement>('.close')
    target?.focus()
  }, [overlay, wiz])

  const exportRows = useCallback(() => {
    if (!isConfId(page)) return
    const list = filterRows(rows[page], page, tab, query, status)
    const csv =
      '﻿' +
      [conf[page].cols, ...list.map((r) => [r[0] + ' / ' + r[1], ...r.slice(2)])]
        .map((r) => r.map((s) => '"' + String(s).replace(/"/g, '""') + '"').join(','))
        .join('\r\n')
    // 현재 화면의 샘플 행을 브라우저에서만 CSV로 저장한다(서버 요청 없음).
    if (typeof URL.createObjectURL === 'function') {
      const u = URL.createObjectURL(new Blob([csv], { type: 'text/csv;charset=utf-8' }))
      const a = document.createElement('a')
      a.href = u
      a.download = 'pg-' + page + '-sample.csv'
      a.click()
      URL.revokeObjectURL(u)
    }
    toast('현재 조회 결과를 내보냈어요')
  }, [page, rows, tab, query, status, toast])

  const detail = useCallback((id: ConfId, key: string) => setOverlay({ kind: 'detail', id, key }), [])

  const startWizard = useCallback(() => {
    setForm({})
    setWiz(0)
    setOverlay({ kind: 'wizard' })
  }, [])

  const ui: Ui = useMemo(
    () => ({
      page,
      tab,
      query,
      status,
      rows,
      legacy,
      wiz,
      form,
      role,
      perms,
      go,
      setTab: (i) => {
        setTabState(i)
        setQuery('')
        setStatus(ALL_STATUS)
      },
      setQuery,
      setStatus,
      resetFilters: () => {
        setQuery('')
        setStatus(ALL_STATUS)
      },
      detail,
      openPanel,
      closePanel,
      startWizard,
      wizardNext: (data) => {
        const next: WizardForm = { ...form, ...(Object.fromEntries(data) as Record<string, string>) }
        setForm(next)
        if (wiz < 3) {
          setWiz(wiz + 1)
          return
        }
        const mid = 'demo' + Date.now().toString().slice(-6)
        const row: Row = [next.name ?? '', mid, next.biz ?? '', next.source ?? '', '심사 중', next.owner ?? '']
        setRows((prev) => ({ ...prev, merchant: [row, ...prev.merchant] }))
        go('merchant')
        toast('사업자를 등록하고 데모 MID ' + mid + '를 발급했어요')
      },
      wizardPrev: () => setWiz((w) => Math.max(0, w - 1)),
      saveSource: (data) => {
        const row: Row = [String(data.get('name') ?? ''), 'SRC-DEMO-' + Date.now().toString().slice(-5), String(data.get('method') ?? ''), String(data.get('mid') ?? ''), '설정 대기', '김 운영']
        setRows((prev) => ({ ...prev, source: [row, ...prev.source] }))
        go('source')
        toast('원천사 데모 기준정보를 등록했어요')
      },
      primaryAction: () => {
        if (!isConfId(page)) return
        if (page === 'merchant') startWizard()
        else if (page === 'source') setOverlay({ kind: 'source' })
        else if (/내보내기/.test(conf[page].action)) exportRows()
        else {
          const first = rows[page][0]
          if (first) detail(page, first[1])
        }
      },
      exportRows,
      toast,
      setRole,
      setPerm: (key, v) => setPerms((p) => ({ ...p, [key]: v })),
    }),
    [page, tab, query, status, rows, legacy, wiz, form, role, perms, go, detail, openPanel, closePanel, startWizard, exportRows, toast],
  )

  const onOverlayClick = (e: MouseEvent<HTMLDivElement>) => {
    if (e.target === e.currentTarget) closePanel()
  }

  return (
    <UiContext.Provider value={ui}>
      <Sidebar />
      <div className="shell">
        <Header />
        <main id="main">
          {page === 'home' ? <HomePage /> : page === 'map' ? <MenuMapPage /> : page === 'access' ? <AccessPage /> : <ModulePage id={page} key={page} />}
        </main>
        <Footer />
      </div>
      <div id="overlay" className="overlay" hidden={!overlay} ref={overlayEl} onClick={onOverlayClick}>
        {overlay?.kind === 'detail' ? (
          <DetailPanel id={overlay.id} rowKey={overlay.key} key={overlay.id + overlay.key} />
        ) : overlay?.kind === 'source' ? (
          <SourceFormPanel />
        ) : overlay?.kind === 'wizard' ? (
          <WizardPanel />
        ) : overlay?.kind === 'accounts' ? (
          <AccountsPanel />
        ) : overlay?.kind === 'audit' ? (
          <AuditPanel />
        ) : overlay?.kind === 'search' ? (
          <SearchModal q={searchQ} setQ={setSearchQ} />
        ) : null}
      </div>
      {/* 원본 style.display 토글은 CSP(style 속성 금지)로 data-open + uiref-csp.css 규칙이 대신한다. */}
      <div id="toast" role="status" data-open={toastOpen ? '' : undefined}>
        {toastText}
      </div>
    </UiContext.Provider>
  )
}
