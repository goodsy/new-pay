import { describe, expect, it, vi } from 'vitest'
import { XSRF_COOKIE, XSRF_HEADER } from './client'
import { createAdminApi } from './adminApi'

/** ADMIN-DEMO-LOGIN-001 §3: 옵션 GET(404 = 꺼짐 → null, 그 밖 오류는 그대로), 로그인 POST(본문 loginName만, CSRF 헤더). */
function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

describe('adminApi — demo quick login', () => {
  it('options: GET /api/auth/demo-quick-login returns the account list', async () => {
    const list = [{ loginName: 'demo-operator', displayName: '데모 운영자', roles: ['OPERATOR'], available: true }]
    const fetch = vi.fn(async (_input: string, _init: RequestInit) => jsonResponse(200, { accounts: list }))
    await expect(createAdminApi({ fetch, cookie: () => '' }).demoQuickLoginOptions!()).resolves.toEqual(list)
    expect(fetch.mock.calls[0]?.[0]).toBe('/api/auth/demo-quick-login')
    expect((fetch.mock.calls[0]?.[1] as RequestInit).method).toBe('GET')
  })

  it('options: 404 (endpoint not registered) means off → null; other errors propagate', async () => {
    const notFound = vi.fn(async () => jsonResponse(404, { error: 'NOT_FOUND' }))
    await expect(createAdminApi({ fetch: notFound, cookie: () => '' }).demoQuickLoginOptions!()).resolves.toBeNull()
    const denied = vi.fn(async () => jsonResponse(403, { error: 'IP_DENIED' }))
    await expect(createAdminApi({ fetch: denied, cookie: () => '' }).demoQuickLoginOptions!()).rejects.toMatchObject({ code: 'IP_DENIED' })
  })

  it('login: POST with only the login name and the CSRF header', async () => {
    const me = { actorId: 'adm_1', displayName: '데모 운영자', roles: ['OPERATOR'] }
    const fetch = vi.fn(async (_input: string, _init: RequestInit) => jsonResponse(200, me))
    await expect(createAdminApi({ fetch, cookie: () => `${XSRF_COOKIE}=tok-1` }).demoQuickLogin!('demo-operator')).resolves.toEqual(me)
    const init = fetch.mock.calls[0]?.[1] as RequestInit
    expect(fetch.mock.calls[0]?.[0]).toBe('/api/auth/demo-quick-login')
    expect(init.method).toBe('POST')
    expect(init.body).toBe('{"loginName":"demo-operator"}')
    expect(new Headers(init.headers).get(XSRF_HEADER)).toBe('tok-1')
  })
})
