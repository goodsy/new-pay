import { useCallback, useEffect, useState, type FormEvent } from 'react'
import type { AccountView, AdminApi, ApprovalRequestInput, ReactivationCause } from '../api/adminApi'
import { OneTimeCode } from '../components/OneTimeCode'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { ChevronDownIcon } from '../components/icons'
import { Field } from '../components/DataBits'
import { FormRow } from '../components/FormRow'
import { Input } from '../components/ui/input'
import { ErrorNotice, StatusNotice } from '../components/Notice'
import { NativeSelect } from '../components/ui/native-select'
import { labelOf } from '../lib/labels'
import { tagClass } from '../lib/statusTone'

/** 2인 승인 요청 선택지(ADMIN 소유 종류 — §4.7.1). ROLE_REACTIVATE는 원인이 필요해 별도 동작([ReactivationRequest] — 서버 원인 후보에서만 고른다, Q-S7-2). */
const REQUESTS: { value: string; label: string; input: (target: string) => ApprovalRequestInput }[] = [
  ...['VIEWER', 'OPERATOR', 'APPROVER', 'SECURITY_ADMIN', 'UNMASK'].map((role) => ({
    value: `ROLE_GRANT:${role}`, label: `역할 부여 · ${labelOf(role)} (${role})`, input: (t: string) => ({ kind: 'ROLE_GRANT', targetActorId: t, grantedRole: role }),
  })),
  ...['ACCOUNT_UNLOCK', 'ACCOUNT_REACTIVATE', 'PASSWORD_RESET', 'TOTP_REENROLL'].map((kind) => ({
    value: kind, label: `${labelOf(kind)} (${kind})`, input: (t: string) => ({ kind, targetActorId: t }),
  })),
]

type RowAction =
  | { type: 'suspend' }
  | { type: 'reissue' }
  | { type: 'revoke'; role: string }
  | { type: 'request'; request: ApprovalRequestInput }

interface Props {
  api: AdminApi
  /** SECURITY_ADMIN 화면 기능 표시(표시만 — 권한 판정은 서버). */
  canManage: boolean
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

/**
 * `ROLE_REACTIVATE` 요청(Q-S7-2): 원인은 서버 후보(그 원인으로 아직 정지된 역할이 있는 재설정 승인 ID·복구 회차)에서만 고른다 — 원인 자유 입력 없음.
 * 원인의 유효성(실행된 재설정·참여자 배제)은 서버의 요청·승인 TX가 판정한다.
 */
function ReactivationRequest({ load, onRequest, onFail }: {
  load: () => Promise<ReactivationCause[]>
  onRequest: (cause: string) => void
  onFail: (e: unknown) => void
}) {
  const [causes, setCauses] = useState<ReactivationCause[] | null>(null)
  const [chosen, setChosen] = useState('')
  const fetchCauses = async () => {
    try {
      const loaded = await load()
      setCauses(loaded)
      setChosen(loaded[0]?.cause ?? '')
    } catch (e) {
      onFail(e)
    }
  }
  if (causes === null) {
    return (
      <button type="button" className="btn self-start" onClick={() => void fetchCauses()}>
        재활성 원인 조회
      </button>
    )
  }
  if (causes.length === 0) return <span className="text-xs text-muted-foreground">재활성할 원인이 없습니다</span>
  return (
    // reviewFix1(1101·1024px 캡처 — 펼친 칸이 표 오른쪽 끝에서 잘림): 승인함 결정 칸(WS5 MINOR-2)과 같이 세로로 — 선택 Field 위, 버튼 아래(DOM 순서 그대로). 새 크기 값 없음.
    <div className="flex flex-col items-start gap-2">
      <Field label="재활성 원인" className="self-stretch text-xs">
        {(describedBy) => (
          <NativeSelect value={chosen} onChange={(e) => setChosen(e.target.value)} aria-describedby={describedBy} className="h-8">
            {causes.map((c) => (
              <option key={c.cause} value={c.cause}>
                {`${c.cause} (${c.roles.join(', ')})`}
              </option>
            ))}
          </NativeSelect>
        )}
      </Field>
      <button type="button" className="btn primary" disabled={!chosen} onClick={() => onRequest(chosen)}>
        재활성 승인 요청
      </button>
    </div>
  )
}

/**
 * 역할 이름표(ADMIN-UI-WORKSPACE-001 계정 관리 — 기존 RoleBadges와 같은 의미): 시안 `.tag`(한국어 + title 원래 code). 정지 역할은 취소선, 없으면 '-'.
 */
function RoleTags({ roles, suspended = false }: { roles: string[]; suspended?: boolean }) {
  if (roles.length === 0) return <span className="subtle">-</span>
  return (
    <span className="flex flex-wrap gap-1">
      {roles.map((role) => (
        <span key={role} className={suspended ? 'tag line-through' : 'tag'} title={role}>
          {labelOf(role)}
        </span>
      ))}
    </span>
  )
}

/**
 * 계정 행 작업(ADMIN-UI-001): 행마다 버튼을 늘어놓지 않고 native `<details>`(Radix 메뉴 없음 — CSP)로 접는다.
 * 순서: 2인 승인 요청(주 동작) → 역할 재활성 → 위험 동작(정지·회수 — 빨강, 구분선 아래).
 */
function AccountRow({ account, canManage, showLogin, onAction, loadCauses, onFail }: {
  account: AccountView
  canManage: boolean
  showLogin: boolean
  onAction: (action: RowAction) => void
  loadCauses: () => Promise<ReactivationCause[]>
  onFail: (e: unknown) => void
}) {
  const [request, setRequest] = useState(REQUESTS[0]!.value)
  const roleLess = account.activeRoles.length === 0 && account.suspendedRoles.length === 0
  return (
    // ADMIN-UI-WORKSPACE-001(계정 관리): 시안 표 칸 — 표시명은 strong, 상태·역할은 .tag(title = code, 색 의미는 기존 Badge tone), 작업 summary·버튼은 .btn.
    // 표시·차단 조건(canManage·정지 여부·역할 유무·PENDING_ENROLLMENT)과 행 이름(aria-label)은 그대로. actor ID는 그리지 않는다(D-ADMIN-UI-004).
    <tr aria-label={`${account.loginName ?? account.displayName} ${account.status}`}>
      {showLogin && <td className="font-mono">{account.loginName ?? ''}</td>}
      <td><strong>{account.displayName}</strong></td>
      <td><span className={tagClass(account.status)} title={account.status}>{labelOf(account.status)}</span></td>
      <td><RoleTags roles={account.activeRoles} /></td>
      <td><RoleTags roles={account.suspendedRoles} suspended /></td>
      {canManage && (
        <td className="align-top">
          <details className="group">
            <summary className="btn inline-flex cursor-pointer list-none items-center gap-1.5 [&::-webkit-details-marker]:hidden">
              작업
              <ChevronDownIcon aria-hidden="true" className="size-4 transition-transform group-open:rotate-180" />
            </summary>
            <div className="mt-2 flex min-w-72 flex-col gap-3 rounded-lg border bg-muted/30 p-3">
              {/* reviewFix1: 요청 칸도 세로로 — 선택 Field 위(펼친 칸 폭에 맞춤), 승인 요청 버튼 아래. */}
              <div className="flex flex-col items-start gap-2">
                <Field label="승인 요청 종류" className="self-stretch text-xs">
                  {(describedBy) => (
                    <NativeSelect value={request} onChange={(e) => setRequest(e.target.value)} aria-describedby={describedBy} className="h-8">
                      {REQUESTS.map((r) => (
                        <option key={r.value} value={r.value}>
                          {r.label}
                        </option>
                      ))}
                    </NativeSelect>
                  )}
                </Field>
                <button
                  type="button"
                  className="btn primary"
                  onClick={() => {
                    const choice = REQUESTS.find((r) => r.value === request)
                    if (choice) onAction({ type: 'request', request: choice.input(account.actorId) })
                  }}
                >
                  승인 요청
                </button>
              </div>
              {account.suspendedRoles.length > 0 && (
                <ReactivationRequest
                  load={loadCauses}
                  onFail={onFail}
                  onRequest={(cause) => onAction({ type: 'request', request: { kind: 'ROLE_REACTIVATE', targetActorId: account.actorId, causeApprovalId: cause } })}
                />
              )}
              {account.status === 'PENDING_ENROLLMENT' && roleLess && (
                <button type="button" className="btn self-start" onClick={() => onAction({ type: 'reissue' })}>
                  등록 코드 재발급
                </button>
              )}
              {(account.status !== 'SUSPENDED' || account.activeRoles.length + account.suspendedRoles.length > 0) && (
                <div className="flex flex-col gap-2 border-t pt-3">
                  <span className="text-xs font-medium text-destructive">위험 작업 — 바로 적용됩니다</span>
                  <div className="flex flex-wrap gap-1.5">
                    {account.status !== 'SUSPENDED' && (
                      // 정지: 승인함 거절(reviewFix1 MINOR-1)과 같은 파괴 동작 token — destructive-solid 바탕·선 + 흰 글자(라이트 약 4.77:1, 다크 #b91c1c 6.47:1).
                      <button type="button" className="btn border-destructive-solid bg-destructive-solid text-white" onClick={() => onAction({ type: 'suspend' })}>
                        정지
                      </button>
                    )}
                    {[...account.activeRoles, ...account.suspendedRoles].map((role, index) => (
                      // 역할 회수: 기존 빨간 테두리·글자(같은 token) 그대로 .btn 위에 — 글자 대비 라이트 흰 바탕 약 4.77:1, 다크 #f87171 on .btn #111a2b 약 6.3:1.
                      <button
                        key={`${role}-${index}`}
                        type="button"
                        title={`${labelOf(role)} 역할 회수`}
                        onClick={() => onAction({ type: 'revoke', role })}
                        className="btn border-destructive/40 text-destructive hover:bg-destructive/10 hover:text-destructive"
                      >
                        {role} 회수
                      </button>
                    ))}
                  </div>
                </div>
              )}
            </div>
          </details>
        </td>
      )}
    </tr>
  )
}

/** 계정 목록(마스킹된 조회)·계정 생성(코드 1회 표시)·1인 명령(정지·재발급·역할 회수)·2인 승인 요청(SECURITY_ADMIN). */
export function AccountsScreen({ api, canManage, onSessionEnded }: Props) {
  const [accounts, setAccounts] = useState<AccountView[]>([])
  const [truncated, setTruncated] = useState(false)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [oneTimeCode, setOneTimeCode] = useState<string | null>(null)
  const [loginName, setLoginName] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [creating, setCreating] = useState(false)

  const fail = useCallback((e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError(code)
  }, [onSessionEnded])

  const load = useCallback(async () => {
    try {
      const page = await api.accounts()
      setAccounts(page.accounts)
      setTruncated(page.truncated === true)
    } catch (e) {
      fail(e)
    }
  }, [api, fail])

  useEffect(() => {
    void load()
  }, [load])

  const act = async (action: () => Promise<unknown>) => {
    setError(null)
    try {
      const result = await action()
      if (typeof result === 'object' && result !== null && 'enrollmentCode' in result && typeof result.enrollmentCode === 'string') {
        setOneTimeCode(result.enrollmentCode)
      }
      await load()
    } catch (e) {
      fail(e)
    }
  }

  const onRowAction = (account: AccountView) => (action: RowAction) => {
    void act(() => {
      switch (action.type) {
        case 'suspend':
          return api.suspend(account.actorId)
        case 'reissue':
          return api.reissue(account.actorId)
        case 'revoke':
          return api.revokeRole(account.actorId, action.role)
        case 'request':
          return api.requestApproval(action.request)
      }
    })
  }

  const create = (event: FormEvent) => {
    event.preventDefault()
    setCreating(true)
    void act(async () => {
      try {
        const result = await api.createAccount(loginName, displayName)
        setLoginName('')
        setDisplayName('')
        return result
      } finally {
        setCreating(false)
      }
    })
  }

  const showLogin = accounts.some((a) => a.loginName !== null)

  // ADMIN-UI-WORKSPACE-001(계정·권한 › 계정 관리): 대사 발견·승인함과 같이 MenuCard flush 탭 패널 바로 안에 시안 구성 — .mapnote(탭 이름 h2 + 설명) → (오류·500개 안내 시) .cardnote
  // → (SECURITY_ADMIN) 카드 제목 줄 .cardtop h3 '새 계정 만들기' + .cardbody(안내 · 폼 줄 2개(입력 + 도움말 — LAYOUT-ROLLOUT B3) · .fieldoffset .btn.primary) → .tablewrap(빈 목록 .empty) → .tablebottom(표시 건수).
  // 등록 코드 1회 표시([OneTimeCode])는 body에 붙는 dialog라 카드 구조에 들어가지 않는다(그대로).
  return (
    <>
      <div className="mapnote">
        <h2>계정 관리</h2>
        {' · '}
        <span>관리자 계정과 역할을 확인합니다. 역할 부여·잠금 해제 같은 변경은 다른 관리자의 승인을 거쳐 적용됩니다.</span>
      </div>
      {(error || truncated) && (
        <div className="cardnote">
          {error && <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>}
          {truncated && <StatusNotice>목록이 길어 앞의 500개만 보입니다.</StatusNotice>}
        </div>
      )}
      {oneTimeCode && <OneTimeCode code={oneTimeCode} onClose={() => setOneTimeCode(null)} />}
      {canManage && (
        <>
          <div className="cardtop">
            <h3>새 계정 만들기</h3>
          </div>
          <div className="cardbody">
            <p className="subtle">만들면 등록 코드가 한 번만 표시됩니다. 역할은 만든 뒤 2인 승인으로 부여합니다.</p>
            {/* LAYOUT-ROLLOUT B3(사용자 결정 2026-11-19 폼 줄 — 시안 .rolebar 대체): 이름 왼쪽 label[for] · 입력 오른쪽 같은 줄(FormRow). 폭 = ADMIN V1 admin_account 열 길이 단계 —
                로그인 이름 lg(login_name VARCHAR(64)) · 표시명 lg(display_name VARCHAR(100)). 아래 한 문단에 있던 도움말 두 문장은 각 줄 입력 열 아래 .fieldnote로(글자·aria-describedby 연결 그대로).
                '계정 만들기'는 입력 열에 맞춘 .fieldoffset(사업자 등록 보조 버튼 선례). 검색 버튼 선례처럼 아이콘·스피너 없이 aria-busy만. */}
            <form onSubmit={create} aria-label="새 계정 만들기">
              <FormRow label="새 로그인 이름" width="lg" hint="로그인 이름은 로그인할 때 쓰는 이름입니다.">
                {(describedBy, id) => (
                  <Input id={id} value={loginName} onChange={(e) => setLoginName(e.target.value)} aria-describedby={describedBy} required />
                )}
              </FormRow>
              <FormRow label="표시명" width="lg" hint="표시명은 화면과 감사 기록에 보이는 이름입니다.">
                {(describedBy, id) => (
                  <Input id={id} value={displayName} onChange={(e) => setDisplayName(e.target.value)} aria-describedby={describedBy} required />
                )}
              </FormRow>
              <div className="fieldoffset">
                <button type="submit" className="btn primary" aria-busy={creating}>
                  계정 만들기
                </button>
              </div>
            </form>
          </div>
        </>
      )}
      <div className="tablewrap">
        <table aria-label="계정 목록">
          <thead>
            <tr>
              {showLogin && <th>로그인 이름</th>}
              <th>표시명</th>
              <th>상태</th>
              <th>활성 역할</th>
              <th>정지 역할</th>
              {canManage && <th>작업</th>}
            </tr>
          </thead>
          <tbody>
            {accounts.map((account) => (
              <AccountRow
                key={account.actorId}
                account={account}
                canManage={canManage}
                showLogin={showLogin}
                onAction={onRowAction(account)}
                loadCauses={async () => (await api.reactivationCauses(account.actorId)).causes}
                onFail={fail}
              />
            ))}
          </tbody>
        </table>
        {accounts.length === 0 && <div className="empty">표시할 계정이 없습니다.</div>}
      </div>
      <div className="tablebottom">
        <span>{`표시 ${accounts.length}건`}</span>
      </div>
    </>
  )
}
