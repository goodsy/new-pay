import { describe, expect, it } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import indexHtml from '../../index.html?raw'
import { App } from '../App'
import { FALLBACK } from '../checkoutData'
import { errorData, pageData } from './fixtures'

/**
 * 결제창 CSP 회귀 방지(CHECKOUT-REACT-001 §4 — style-src 'self'·script-src 'self', 'unsafe-inline' 없음, internal-admin ADMIN-UI-001과 같은 방식):
 * 화면이 런타임에 <style> 요소·style 속성·외부 link/script를 만들지 않는다. index.html(서버 shell 원본)에도 inline script·style이 없다.
 */
function expectNoRuntimeStyles() {
  expect(document.querySelectorAll('style')).toHaveLength(0)
  expect(document.querySelectorAll('[style]')).toHaveLength(0)
  expect(document.querySelectorAll('link, script')).toHaveLength(0)
}

describe('CHECKOUT-REACT-001: no runtime <style>, style attribute or injected resource', () => {
  it('page before and after choosing a card company, with the cancel confirmation open', async () => {
    render(<App data={pageData({ notice: '선택한 카드사 또는 할부를 사용할 수 없습니다.' })} />)
    expectNoRuntimeStyles()
    await userEvent.click(screen.getByRole('radio', { name: '가상카드A' }))
    await userEvent.click(screen.getByRole('button', { name: '결제창 닫기' }))
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
    expectNoRuntimeStyles()
  })

  it('error and fallback screens', () => {
    const error = render(<App data={errorData()} />)
    expectNoRuntimeStyles()
    error.unmount()
    render(<App data={FALLBACK} />)
    expectNoRuntimeStyles()
  })

  it('index.html (the server shell source) has one module script with src and no inline script, style or handler', () => {
    const html = indexHtml
    const scripts = html.match(/<script\b[^>]*>[\s\S]*?<\/script>/gi) ?? []
    expect(scripts).toEqual(['<script type="module" src="/src/main.tsx"></script>'])
    expect(html).not.toMatch(/<style\b/i)
    expect(html).not.toMatch(/\sstyle\s*=/i)
    expect(html).not.toMatch(/\son[a-z]+\s*=/i)
    expect(html).not.toMatch(/https?:\/\//i)
    expect(html.match(/<div id="root"><\/div>/g)).toHaveLength(1)
  })
})
