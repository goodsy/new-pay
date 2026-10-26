import { useCallback, useEffect, useMemo, useState } from 'react'
import { createAdminApi, type AdminApi, type Me } from './api/adminApi'
import type { ConsoleErrorCode } from './errors/errorCodes'
import { AppLayout } from './layout/AppLayout'
import { AccountsScreen } from './screens/AccountsScreen'
import { ApprovalsScreen } from './screens/ApprovalsScreen'
import { AuditScreen } from './screens/AuditScreen'
import { EnrollmentScreen } from './screens/EnrollmentScreen'
import { LoginScreen } from './screens/LoginScreen'

type Page = 'approvals' | 'mine' | 'accounts' | 'audit'

/** 역할 → 보이는 메뉴(표시만 — 서버가 매 요청 계정 현재 행으로 판정한다, §4.5.1). */
function pagesFor(roles: string[]): Page[] {
  const has = (role: string) => roles.includes(role)
  const pages: Page[] = []
  if (has('APPROVER')) pages.push('approvals')
  if (has('SECURITY_ADMIN')) pages.push('mine')
  if (roles.some((r) => r !== 'UNMASK')) pages.push('accounts')
  pages.push('audit')
  return pages
}

const LABELS: Record<Page, string> = { approvals: '승인함', mine: '내 요청', accounts: '계정', audit: '감사' }

/**
 * 관리자 콘솔(S7 — 같은 출처 정적 번들, 라우터·저장소 없음: 화면 상태는 메모리에만, URL에 비밀·코드 없음).
 * 세션이 끝난 응답(SESSION_REPLACED·SESSION_EXPIRED·UNAUTHENTICATED·CSRF_MISSING)은 로그인 화면으로 돌아가 이유를 안내한다.
 */
export function App({ api: injected }: { api?: AdminApi } = {}) {
  const api = useMemo(() => injected ?? createAdminApi(), [injected])
  const [me, setMe] = useState<Me | null>(null)
  const [checked, setChecked] = useState(false)
  const [mode, setMode] = useState<'login' | 'enroll'>('login')
  const [notice, setNotice] = useState<ConsoleErrorCode | undefined>(undefined)
  const [page, setPage] = useState<Page>('audit')

  const signedIn = useCallback((account: Me) => {
    setMe(account)
    setNotice(undefined)
    setPage(pagesFor(account.roles)[0] ?? 'audit')
  }, [])

  useEffect(() => {
    let alive = true
    api.me().then((account) => alive && signedIn(account)).catch(() => undefined).finally(() => alive && setChecked(true))
    return () => {
      alive = false
    }
  }, [api, signedIn])

  const sessionEnded = useCallback((code: ConsoleErrorCode) => {
    setMe(null)
    setMode('login')
    setNotice(code)
  }, [])

  const logout = async () => {
    try {
      await api.logout()
    } finally {
      setMe(null)
      setMode('login')
    }
  }

  if (!me) {
    return (
      <AppLayout>
        {!checked ? null : mode === 'enroll' ? (
          <EnrollmentScreen api={api} onDone={() => setMode('login')} />
        ) : (
          <LoginScreen api={api} onAuthenticated={signedIn} onEnroll={() => setMode('enroll')} notice={notice} />
        )}
      </AppLayout>
    )
  }

  const pages = pagesFor(me.roles)
  return (
    <AppLayout
      header={
        <nav>
          <span>{me.displayName}</span>
          {pages.map((p) => (
            <button key={p} type="button" aria-pressed={page === p} onClick={() => setPage(p)}>
              {LABELS[p]}
            </button>
          ))}
          <button type="button" onClick={logout}>
            로그아웃
          </button>
        </nav>
      }
    >
      {page === 'approvals' && <ApprovalsScreen api={api} view="pending" canDecide onSessionEnded={sessionEnded} />}
      {page === 'mine' && <ApprovalsScreen api={api} view="mine" canDecide={false} onSessionEnded={sessionEnded} />}
      {page === 'accounts' && <AccountsScreen api={api} canManage={me.roles.includes('SECURITY_ADMIN')} onSessionEnded={sessionEnded} />}
      {page === 'audit' && <AuditScreen api={api} onSessionEnded={sessionEnded} />}
    </AppLayout>
  )
}
