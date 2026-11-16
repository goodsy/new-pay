import { createContext, useContext } from 'react'
import type { ConfId, LegacyRow, PageId, Row } from './data'

export type Overlay =
  | { kind: 'detail'; id: ConfId; key: string }
  | { kind: 'source' }
  | { kind: 'wizard' }
  | { kind: 'accounts' }
  | { kind: 'audit' }
  | { kind: 'search' }

export type LegacyState = { state: 'loading' } | { state: 'ok'; rows: readonly LegacyRow[] } | { state: 'error' }

export type WizardForm = Partial<Record<'name' | 'biz' | 'owner' | 'contract' | 'source' | 'service' | 'cycle' | 'fee', string>>

/** app.js 전역 상태(page/tab/query/status/legacy/form/wiz/role/perms)와 동작을 React 상태로 옮긴 것 */
export interface Ui {
  page: PageId
  tab: number
  query: string
  status: string
  rows: Readonly<Record<ConfId, readonly Row[]>>
  legacy: LegacyState
  wiz: number
  form: WizardForm
  role: string
  perms: Readonly<Record<string, boolean>>
  go: (id: PageId) => void
  setTab: (i: number) => void
  setQuery: (q: string) => void
  setStatus: (s: string) => void
  resetFilters: () => void
  detail: (id: ConfId, key: string) => void
  openPanel: (o: Overlay) => void
  closePanel: () => void
  startWizard: () => void
  wizardNext: (data: FormData) => void
  wizardPrev: () => void
  saveSource: (data: FormData) => void
  primaryAction: () => void
  exportRows: () => void
  toast: (t: string) => void
  setRole: (r: string) => void
  setPerm: (key: string, v: boolean) => void
}

export const UiContext = createContext<Ui | null>(null)

export function useUi(): Ui {
  const ui = useContext(UiContext)
  if (!ui) throw new Error('UiContext is missing')
  return ui
}
