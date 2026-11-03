import { describe, expect, it } from 'vitest'
import { FALLBACK, amountLabel, parseCheckoutData } from './checkoutData'
import { errorData, HANDLE, pageData } from './test/fixtures'

const json = (value: unknown) => JSON.stringify(value)

describe('parseCheckoutData (CHECKOUT-REACT-001 §3 — server data, defensive shape check)', () => {
  it('accepts the server page and error data as they are', () => {
    expect(parseCheckoutData(json(pageData()))).toEqual(pageData())
    expect(parseCheckoutData(json(errorData()))).toEqual(errorData())
    expect(parseCheckoutData(json(errorData({ retryUrl: null, failRedirect: null })))).toEqual(errorData({ retryUrl: null, failRedirect: null }))
    expect(parseCheckoutData(json(pageData({ orderName: null, notice: '선택한 카드사 또는 할부를 사용할 수 없습니다.' }))).screen).toBe('page')
  })

  it('a missing, non-JSON or unknown-screen payload is the fixed temporary-error screen without links', () => {
    for (const raw of [undefined, '', '{', 'null', '[]', json({ screen: 'other' }), json({ screen: 'page' })]) {
      expect(parseCheckoutData(raw)).toEqual(FALLBACK)
    }
    expect(FALLBACK).toEqual({ screen: 'error', message: '일시적인 오류입니다.', retryUrl: null, failRedirect: null })
  })

  it('form actions must be this checkout handle path on the same origin', () => {
    const bad = [
      'https://evil.example.test/checkout/s/' + HANDLE + '/authentication',
      '//evil.example.test/checkout/s/' + HANDLE + '/authentication',
      '/checkout/s/short/authentication',
      '/checkout/s/' + HANDLE + '/authentication?x=1',
      'javascript:alert(1)',
    ]
    for (const action of bad) {
      expect(parseCheckoutData(json(pageData({ authenticationAction: action })))).toEqual(FALLBACK)
      expect(parseCheckoutData(json(pageData({ cancelAction: action.replace('authentication', 'cancel') })))).toEqual(FALLBACK)
    }
    expect(parseCheckoutData(json(pageData({ authenticationAction: `/checkout/s/${HANDLE}/cancel` })))).toEqual(FALLBACK)
  })

  it('the CSRF field name is the checkout one and the token is non-empty', () => {
    expect(parseCheckoutData(json(pageData({ csrfParameter: 'csrf' as never })))).toEqual(FALLBACK)
    expect(parseCheckoutData(json(pageData({ csrfToken: '' })))).toEqual(FALLBACK)
  })

  it('choices are {value,label} strings; extra or missing fields are rejected', () => {
    expect(parseCheckoutData(json(pageData({ cardCompanies: [{ value: 'SIMISSA' }] as never })))).toEqual(FALLBACK)
    expect(parseCheckoutData(json(pageData({ plans: 'LUMP_SUM' as never })))).toEqual(FALLBACK)
    expect(parseCheckoutData(json({ ...pageData(), cardNumber: '4111111111111111' }))).toEqual(FALLBACK)
    expect(parseCheckoutData(json({ ...errorData(), detail: 'x' }))).toEqual(FALLBACK)
  })

  it('error links: retry only within this checkout path, fail only to an absolute https URL', () => {
    for (const retryUrl of ['https://evil.example.test/', '//evil.example.test/', 'javascript:alert(1)', '/v1/payments', '/checkout/s/short']) {
      expect(parseCheckoutData(json(errorData({ retryUrl })))).toEqual(FALLBACK)
    }
    expect(parseCheckoutData(json(errorData({ retryUrl: `/checkout/s/${HANDLE}/authentication/return?resultRef=r1&state=s1` }))).screen).toBe('error')
    for (const failRedirect of ['http://shop.example.test/fail', 'javascript:alert(1)', '/checkout/s/' + HANDLE, 'data:text/html,x', 'https:/shop.example.test']) {
      expect(parseCheckoutData(json(errorData({ failRedirect })))).toEqual(FALLBACK)
    }
  })
})

describe('amountLabel (display only — the server formats the amount)', () => {
  it('KRW reads as won; other currencies keep the server amount and the currency code', () => {
    expect(amountLabel('10,000', 'KRW')).toBe('10,000원')
    expect(amountLabel('10.50', 'USD')).toBe('10.50 USD')
  })
})
