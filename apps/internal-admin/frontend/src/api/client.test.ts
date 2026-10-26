import { describe, expect, it, vi } from 'vitest'
import { ApiError, XSRF_COOKIE, XSRF_HEADER, apiFetch, readCookie } from './client'

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

function deps(response: Response, cookie = '') {
  const fetch = vi.fn(async (_input: string, _init: RequestInit) => response)
  return { fetch, cookie: () => cookie }
}

function headersOf(fetch: ReturnType<typeof vi.fn>): Headers {
  const init = fetch.mock.calls[0]?.[1] as RequestInit
  return new Headers(init.headers)
}

describe('apiFetch', () => {
  it('sends GET same-origin without a CSRF header', async () => {
    const d = deps(jsonResponse(200, { ok: true }), `${XSRF_COOKIE}=tok-1`)
    await expect(apiFetch('/api/me', {}, d)).resolves.toEqual({ ok: true })
    const init = d.fetch.mock.calls[0]?.[1] as RequestInit
    expect(init.credentials).toBe('same-origin')
    expect(init.method).toBe('GET')
    expect(headersOf(d.fetch).has(XSRF_HEADER)).toBe(false)
    expect(headersOf(d.fetch).get('Accept')).toBe('application/json')
  })

  it('attaches the X-XSRF-TOKEN header from the XSRF cookie on state-changing requests', async () => {
    const d = deps(jsonResponse(200, { ok: true }), `other=1; ${XSRF_COOKIE}=tok-2; last=x`)
    await apiFetch('/api/approvals/a1/approve', { method: 'POST', body: { totpCode: '123456' } }, d)
    expect(headersOf(d.fetch).get(XSRF_HEADER)).toBe('tok-2')
    expect(headersOf(d.fetch).get('Content-Type')).toBe('application/json')
    expect((d.fetch.mock.calls[0]?.[1] as RequestInit).body).toBe('{"totpCode":"123456"}')
  })

  it('refuses a state-changing request without the XSRF cookie and does not send it', async () => {
    const d = deps(jsonResponse(200, {}))
    await expect(apiFetch('/api/x', { method: 'POST' }, d)).rejects.toMatchObject({ code: 'CSRF_MISSING' })
    expect(d.fetch).not.toHaveBeenCalled()
  })

  it('rejects paths outside same-origin /api/', async () => {
    const d = deps(jsonResponse(200, {}))
    await expect(apiFetch('https://evil.example/api/x', {}, d)).rejects.toThrow()
    await expect(apiFetch('/assets/x.js', {}, d)).rejects.toThrow()
    expect(d.fetch).not.toHaveBeenCalled()
  })

  it('maps a known server error code (SESSION_REPLACED)', async () => {
    const d = deps(jsonResponse(401, { error: 'SESSION_REPLACED' }))
    const error = await apiFetch('/api/me', {}, d).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ status: 401, code: 'SESSION_REPLACED' })
  })

  it('treats a known server code that contradicts the HTTP status as a generic error', async () => {
    // 401이어야 할 code가 409로 오거나 403 code가 500으로 오면 그 code를 믿지 않는다(S1 리뷰 n5).
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(409, { error: 'SESSION_REPLACED' })))).rejects.toMatchObject({ code: 'REQUEST_REJECTED' })
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(500, { error: 'FORBIDDEN' })))).rejects.toMatchObject({ code: 'SERVER_ERROR' })
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(403, { error: 'RETRY_LATER' })))).rejects.toMatchObject({ code: 'REQUEST_REJECTED' })
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(401, { error: 'SESSION_EXPIRED' })))).rejects.toMatchObject({ code: 'SESSION_EXPIRED' })
  })

  it('falls back to the status when the server code is unknown or the body is not JSON', async () => {
    // S7 리뷰 m-1(Coordinator 판정): 표에 없는 문자열 code는 상태와 무관하게 일반 거절(본문 code가 없을 때만 상태로 분류).
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(403, { error: '<script>x</script>' })))).rejects.toMatchObject({ code: 'REQUEST_REJECTED' })
    // S8 리뷰 nit-2: 본문 code가 없으면 상태로 분류.
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(403, {})))).rejects.toMatchObject({ code: 'FORBIDDEN' })
    await expect(apiFetch('/api/x', {}, deps(jsonResponse(401, {})))).rejects.toMatchObject({ code: 'UNAUTHENTICATED' })
    await expect(apiFetch('/api/x', {}, deps(new Response('busy', { status: 409 })))).rejects.toMatchObject({ code: 'RETRY_LATER' })
    await expect(apiFetch('/api/x', {}, deps(new Response('boom', { status: 502 })))).rejects.toMatchObject({ code: 'SERVER_ERROR' })
    await expect(apiFetch('/api/x', {}, deps(new Response('', { status: 404 })))).rejects.toMatchObject({ code: 'NOT_FOUND' })
  })
})

describe('readCookie', () => {
  it('reads one cookie by exact name', () => {
    expect(readCookie(XSRF_COOKIE, `a=1; ${XSRF_COOKIE}=v; ${XSRF_COOKIE}-old=w`)).toBe('v')
    expect(readCookie(XSRF_COOKIE, `x${XSRF_COOKIE}=v`)).toBeUndefined()
    expect(readCookie(XSRF_COOKIE, '')).toBeUndefined()
  })
})
