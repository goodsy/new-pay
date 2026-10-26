import { apiFetch, type ApiDeps } from './client'

export interface Me {
  actorId: string
  displayName: string
  roles: string[]
}

export interface EnrollStart {
  purpose: string
  needsPassword: boolean
  needsTotp: boolean
}

export interface AccountView {
  actorId: string
  /** SECURITY_ADMIN 조회에만 있다(D-ADMIN1-017 — 서버가 그 밖 역할에는 보내지 않는다). */
  loginName: string | null
  displayName: string
  status: string
  activeRoles: string[]
  suspendedRoles: string[]
  pendingPurpose: string | null
}

export interface ApprovalView {
  approvalId: string
  kind: string
  targetActorId: string
  targetSnapshot: string
  grantedRole: string | null
  causeApprovalId: string | null
  requestedBy: string
  requestedByDisplayName: string
  requestedAt: string
  expiresAt: string
  status: string
  decidedBy: string | null
  decidedByDisplayName: string | null
  decidedAt: string | null
  resultCode: string | null
}

/** `ROLE_REACTIVATE` 원인 후보(Q-S7-2 — 서버가 역할 이벤트로 정한다). 화면은 이 목록에서만 고른다(자유 입력 없음). */
export interface ReactivationCause {
  cause: string
  roles: string[]
}

export interface ApprovalRequestInput {
  kind: string
  targetActorId: string
  grantedRole?: string
  causeApprovalId?: string
}

export interface AuditEntry {
  eventId: number
  occurredAt: string
  actorKind: string
  actorId: string | null
  actorDisplayName: string | null
  executorActorId: string | null
  action: string
  targetActorId: string | null
  resultCode: string
  approvalId: string | null
  correlationId: string
  detail: string | null
  reason: string | null
}

export interface AuditFilterInput {
  actorId?: string
  targetActorId?: string
  action?: string
}

/**
 * 화면이 쓰는 서버 API(같은 출처 `/api/` — [apiFetch] 하나로). 등록 코드·TOTP 시크릿은 응답을 받은 화면 상태에만 잠깐 두고 저장소·URL에 쓰지 않는다.
 * 권한 판정은 서버가 한다.
 */
export interface AdminApi {
  csrf(): Promise<void>
  me(): Promise<Me>
  password(loginName: string, password: string): Promise<{ next: string }>
  totp(code: string): Promise<Me>
  logout(): Promise<void>
  enrollStart(code: string): Promise<EnrollStart>
  enrollPassword(password: string): Promise<void>
  enrollTotp(): Promise<{ otpauthUri: string }>
  enrollComplete(totpCode: string | undefined): Promise<{ status: string }>
  /** [truncated]면 서버 상한(500행)에서 잘렸다(S8 리뷰 nit-5). */
  accounts(): Promise<{ accounts: AccountView[]; truncated?: boolean }>
  createAccount(loginName: string, displayName: string): Promise<{ actorId: string; enrollmentCode: string }>
  suspend(actorId: string): Promise<{ status: string }>
  reissue(actorId: string): Promise<{ actorId: string; enrollmentCode: string }>
  revokeRole(actorId: string, role: string): Promise<{ status: string }>
  reactivationCauses(actorId: string): Promise<{ causes: ReactivationCause[] }>
  approvals(view: 'pending' | 'mine'): Promise<{ approvals: ApprovalView[] }>
  requestApproval(input: ApprovalRequestInput): Promise<{ approvalId: string }>
  approve(approvalId: string, totpCode: string): Promise<{ approvalId: string; status: string; enrollmentCode?: string }>
  reject(approvalId: string, totpCode: string): Promise<{ status: string }>
  withdraw(approvalId: string): Promise<{ status: string }>
  audit(filter: AuditFilterInput): Promise<{ entries: AuditEntry[] }>
}

const segment = (value: string) => encodeURIComponent(value)

/** 필터만 쿼리로(빈 값 생략). 범위·역할은 보내지 않는다 — 서버가 세션 계정 현재 행으로 정한다. */
export function auditQuery(filter: AuditFilterInput): string {
  const params = new URLSearchParams()
  for (const [key, value] of Object.entries(filter)) if (value) params.set(key, value)
  const text = params.toString()
  return text ? `?${text}` : ''
}

export function createAdminApi(deps?: ApiDeps): AdminApi {
  const call = <T,>(path: string, method = 'GET', body?: unknown) => apiFetch<T>(path, { method, body }, deps)
  return {
    csrf: () => call<void>('/api/auth/csrf'),
    me: () => call<Me>('/api/auth/me'),
    password: (loginName, password) => call('/api/auth/password', 'POST', { loginName, password }),
    totp: (code) => call<Me>('/api/auth/totp', 'POST', { code }),
    logout: () => call<void>('/api/auth/logout', 'POST'),
    enrollStart: (code) => call<EnrollStart>('/api/enroll/start', 'POST', { code }),
    enrollPassword: (password) => call<void>('/api/enroll/password', 'POST', { password }),
    enrollTotp: () => call('/api/enroll/totp', 'POST'),
    enrollComplete: (totpCode) => call('/api/enroll/complete', 'POST', totpCode === undefined ? {} : { totpCode }),
    accounts: () => call('/api/accounts'),
    createAccount: (loginName, displayName) => call('/api/accounts', 'POST', { loginName, displayName }),
    suspend: (actorId) => call(`/api/accounts/${segment(actorId)}/suspend`, 'POST'),
    reissue: (actorId) => call(`/api/accounts/${segment(actorId)}/enrollment-code`, 'POST'),
    revokeRole: (actorId, role) => call(`/api/accounts/${segment(actorId)}/roles/${segment(role)}/revoke`, 'POST'),
    reactivationCauses: (actorId) => call(`/api/accounts/${segment(actorId)}/reactivation-causes`),
    approvals: (view) => call(`/api/approvals?view=${view}`),
    requestApproval: (input) => call('/api/approvals', 'POST', input),
    approve: (approvalId, totpCode) => call(`/api/approvals/${segment(approvalId)}/approve`, 'POST', { totpCode }),
    reject: (approvalId, totpCode) => call(`/api/approvals/${segment(approvalId)}/reject`, 'POST', { totpCode }),
    withdraw: (approvalId) => call(`/api/approvals/${segment(approvalId)}/withdraw`, 'POST'),
    audit: (filter) => call(`/api/audit${auditQuery(filter)}`),
  }
}
