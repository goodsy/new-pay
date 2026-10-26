import { type ConsoleErrorCode, errorCodeFrom } from '../errors/errorCodes'

/** 서버가 세션 저장 CSRF 토큰을 전달하는 쿠키(HttpOnly 아님)와, 상태 변경 요청에서 돌려보내는 헤더(D-10). */
export const XSRF_COOKIE = '__Host-PCADMIN-XSRF'
export const XSRF_HEADER = 'X-XSRF-TOKEN'

const SAFE_METHODS = new Set(['GET', 'HEAD'])

export class ApiError extends Error {
  readonly status: number
  readonly code: ConsoleErrorCode

  constructor(status: number, code: ConsoleErrorCode) {
    super(code)
    this.name = 'ApiError'
    this.status = status
    this.code = code
  }
}

export interface ApiRequest {
  method?: string
  body?: unknown
}

export interface ApiDeps {
  fetch: (input: string, init: RequestInit) => Promise<Response>
  cookie: () => string
}

const browserDeps: ApiDeps = {
  fetch: (input, init) => globalThis.fetch(input, init),
  cookie: () => document.cookie,
}

export function readCookie(name: string, cookieHeader: string): string | undefined {
  for (const part of cookieHeader.split(';')) {
    const index = part.indexOf('=')
    if (index < 0) continue
    if (part.slice(0, index).trim() === name) return part.slice(index + 1).trim()
  }
  return undefined
}

/**
 * 같은 출처 `/api/` JSON 호출의 단일 진입점. 세션 쿠키는 브라우저가 보낸다(`credentials: 'same-origin'`).
 * 상태 변경 요청은 XSRF 쿠키 값을 헤더로 돌려보내고, 쿠키가 없으면 보내지 않는다(서버도 거부한다).
 * 권한 판정은 서버가 하고 이 함수는 표시용 오류 code만 만든다.
 */
export async function apiFetch<T>(path: string, request: ApiRequest = {}, deps: ApiDeps = browserDeps): Promise<T> {
  if (!path.startsWith('/api/')) throw new Error('apiFetch only calls same-origin /api/ paths')
  const method = (request.method ?? 'GET').toUpperCase()
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (request.body !== undefined) headers['Content-Type'] = 'application/json'
  if (!SAFE_METHODS.has(method)) {
    const token = readCookie(XSRF_COOKIE, deps.cookie())
    if (!token) throw new ApiError(0, 'CSRF_MISSING')
    headers[XSRF_HEADER] = token
  }
  const response = await deps.fetch(path, {
    method,
    headers,
    body: request.body === undefined ? undefined : JSON.stringify(request.body),
    credentials: 'same-origin',
    cache: 'no-store',
    redirect: 'error',
  })
  if (response.ok) {
    return (response.status === 204 ? undefined : await response.json()) as T
  }
  const body: unknown = await response.json().catch(() => null)
  throw new ApiError(response.status, errorCodeFrom(response.status, body))
}
