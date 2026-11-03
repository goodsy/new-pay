import { describe, expect, it } from 'vitest'
import { readCheckoutData } from './bootstrap'
import { FALLBACK } from './checkoutData'
import { pageData } from './test/fixtures'

describe('bootstrap reads only the server data attribute on #root', () => {
  it('parses data-checkout; a missing root or attribute is the fallback screen', () => {
    const root = document.createElement('div')
    root.setAttribute('data-checkout', JSON.stringify(pageData()))
    expect(readCheckoutData(root)).toEqual(pageData())
    expect(readCheckoutData(document.createElement('div'))).toEqual(FALLBACK)
    expect(readCheckoutData(null)).toEqual(FALLBACK)
  })
})
