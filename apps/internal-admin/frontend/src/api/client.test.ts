import { describe, expect, it, vi } from 'vitest'
import { ApiError, XSRF_COOKIE, XSRF_HEADER, apiFetch, readCookie } from './client'
import { createAdminApi } from './adminApi'

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

describe('apiFetch — I-111 input refusal detail (S8)', () => {
  it('keeps a known field and reason code of a 400 INVALID_INPUT (never the input value); unknown names are dropped', async () => {
    const card = deps(jsonResponse(400, { error: 'INVALID_INPUT', field: 'purposeReference', reason: 'CARD_NUMBER_PATTERN' }), `${XSRF_COOKIE}=t`)
    await expect(apiFetch('/api/approvals/operator-correction-cancel', { method: 'POST', body: {} }, card)).rejects.toMatchObject({
      code: 'INVALID_INPUT', detail: { field: 'purposeReference', reason: 'CARD_NUMBER_PATTERN' },
    })
    const odd = deps(jsonResponse(400, { error: 'INVALID_INPUT', field: '<script>', reason: 'whatever' }), `${XSRF_COOKIE}=t`)
    const error = await apiFetch('/api/approvals/operator-correction-cancel', { method: 'POST', body: {} }, odd).catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect((error as ApiError).detail).toBeUndefined()
  })
})

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

describe('createAdminApi — MERCHANT-OPS-001 §4.1 merchant lookup calls', () => {
  it('search is a POST with the criteria in the JSON body and the CSRF header (the registration number never goes to the URL, R-3)', async () => {
    const d = deps(jsonResponse(200, { items: [], nextCursor: null }), `${XSRF_COOKIE}=tok-m`)
    await createAdminApi(d).merchantSearch({ registrationNumber: '1234567890', lifecycle: 'ACTIVE' })
    const [path, init] = d.fetch.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/merchant-searches')
    expect(init.method).toBe('POST')
    expect(JSON.parse(init.body as string)).toEqual({ registrationNumber: '1234567890', lifecycle: 'ACTIVE' })
    expect(headersOf(d.fetch).get(XSRF_HEADER)).toBe('tok-m')
    expect(path).not.toContain('1234567890')
  })

  it('detail and history are GETs; history sends after only for later pages; the id is one encoded path segment', async () => {
    // 응답 본문은 한 번만 읽히므로 호출마다 새 응답.
    const d = { fetch: vi.fn(async (_input: string, _init: RequestInit) => jsonResponse(200, {})), cookie: () => '' }
    const api = createAdminApi(d)
    await api.merchantDetail('mer_0000000000000000000000000A')
    await api.merchantHistory('mer_0000000000000000000000000A')
    await api.merchantHistory('mer_0000000000000000000000000A', 57)
    await api.merchantDetail('a/../b')
    expect(d.fetch.mock.calls.map(([path, init]) => [path, (init as RequestInit).method])).toEqual([
      ['/api/merchants/mer_0000000000000000000000000A', 'GET'],
      ['/api/merchants/mer_0000000000000000000000000A/history', 'GET'],
      ['/api/merchants/mer_0000000000000000000000000A/history?after=57', 'GET'],
      ['/api/merchants/a%2F..%2Fb', 'GET'],
    ])
  })
})

describe('createAdminApi — ADMIN-HOME-KPI-001 §4.2 home KPI call', () => {
  it('GET /api/home-kpis (spec path, not the provisional /api/home/kpis) with no body and no CSRF header; the document is passed through as-is', async () => {
    const doc = { asOf: '2026-11-15T01:30:00.000Z', businessDate: '2026-11-15', timeZone: 'Asia/Seoul', from: '2026-11-14T15:00:00.000Z', items: [] }
    const d = deps(jsonResponse(200, doc), `${XSRF_COOKIE}=tok-k`)
    expect(await createAdminApi(d).getHomeKpis()).toEqual(doc)
    const [path, init] = d.fetch.mock.calls[0] as [string, RequestInit]
    expect(path).toBe('/api/home-kpis')
    expect(init.method).toBe('GET')
    expect(init.body).toBeUndefined()
    expect(headersOf(d.fetch).has(XSRF_HEADER)).toBe(false)
  })
})
