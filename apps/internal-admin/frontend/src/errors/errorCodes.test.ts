import { describe, expect, it } from 'vitest'
import { errorCodeFrom } from './errorCodes'

describe('errorCodeFrom (S7 — every fixed code of the JSON API)', () => {
  it('keeps business codes only with the HTTP status the server uses for them', () => {
    expect(errorCodeFrom(401, { error: 'INVALID_CREDENTIALS' })).toBe('INVALID_CREDENTIALS')
    expect(errorCodeFrom(403, { error: 'IP_DENIED' })).toBe('IP_DENIED')
    expect(errorCodeFrom(403, { error: 'CREDENTIAL_ISSUER' })).toBe('CREDENTIAL_ISSUER')
    expect(errorCodeFrom(409, { error: 'CREDENTIAL_ISSUER' })).toBe('CREDENTIAL_ISSUER')
    expect(errorCodeFrom(403, { error: 'STEP_UP_FAILED' })).toBe('STEP_UP_FAILED')
    expect(errorCodeFrom(403, { error: 'SELF_APPROVAL' })).toBe('SELF_APPROVAL')
    expect(errorCodeFrom(409, { error: 'ALREADY_DECIDED' })).toBe('ALREADY_DECIDED')
    expect(errorCodeFrom(409, { error: 'DUPLICATE_PENDING' })).toBe('DUPLICATE_PENDING')
    expect(errorCodeFrom(400, { error: 'INVALID_INPUT' })).toBe('INVALID_INPUT')
    expect(errorCodeFrom(400, { error: 'ENROLLMENT_CODE_INVALID' })).toBe('ENROLLMENT_CODE_INVALID')
    expect(errorCodeFrom(400, { error: 'PASSWORD_POLICY' })).toBe('PASSWORD_POLICY')
    expect(errorCodeFrom(500, { error: 'INTERNAL_INTEGRITY' })).toBe('INTERNAL_INTEGRITY')
    expect(errorCodeFrom(503, { error: 'AUDIT_UNAVAILABLE' })).toBe('AUDIT_UNAVAILABLE')
  })

  it('S7 review m-1: a string code outside the table is REQUEST_REJECTED; the status decides only when there is no body code', () => {
    expect(errorCodeFrom(409, { error: 'ROLE_NOT_HELD' })).toBe('REQUEST_REJECTED')
    expect(errorCodeFrom(403, { error: 'SOMETHING_NEW' })).toBe('REQUEST_REJECTED')
    expect(errorCodeFrom(409, null)).toBe('RETRY_LATER')
    // S8 리뷰 nit-1: 401은 본문 code와 무관하게 세션 종료(알려진 401 code는 그대로).
    expect(errorCodeFrom(401, { error: 'SOMETHING_NEW' })).toBe('UNAUTHENTICATED')
    expect(errorCodeFrom(401, { error: 'RETRY_LATER' })).toBe('UNAUTHENTICATED')
    expect(errorCodeFrom(401, { error: 'SESSION_EXPIRED' })).toBe('SESSION_EXPIRED')
    expect(errorCodeFrom(403, {})).toBe('FORBIDDEN')
  })

  it('S8: operator correction cancel codes keep their statuses (payment lookup, PAYMENT unavailable, rate limit, unresolved, busy target)', () => {
    expect(errorCodeFrom(404, { error: 'PAYMENT_NOT_FOUND' })).toBe('PAYMENT_NOT_FOUND')
    expect(errorCodeFrom(400, { error: 'PAYMENT_NOT_FOUND' })).toBe('PAYMENT_NOT_FOUND')
    expect(errorCodeFrom(503, { error: 'PAYMENT_UNAVAILABLE' })).toBe('PAYMENT_UNAVAILABLE')
    expect(errorCodeFrom(429, { error: 'RATE_LIMITED' })).toBe('RATE_LIMITED')
    expect(errorCodeFrom(409, { error: 'DUPLICATE_UNRESOLVED' })).toBe('DUPLICATE_UNRESOLVED')
    expect(errorCodeFrom(409, { error: 'TARGET_BUSY' })).toBe('TARGET_BUSY')
    expect(errorCodeFrom(200, { error: 'PAYMENT_UNAVAILABLE' })).toBe('REQUEST_REJECTED')
  })

  it('distrusts a business code arriving with the wrong status', () => {
    expect(errorCodeFrom(500, { error: 'IP_DENIED' })).toBe('SERVER_ERROR')
    expect(errorCodeFrom(200, { error: 'ALREADY_DECIDED' })).toBe('REQUEST_REJECTED')
    expect(errorCodeFrom(409, { error: 'INVALID_CREDENTIALS' })).toBe('REQUEST_REJECTED')
  })
})
