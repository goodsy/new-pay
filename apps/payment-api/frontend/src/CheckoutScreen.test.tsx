import { describe, expect, it } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { App } from './App'
import { HANDLE, pageData } from './test/fixtures'

function formOf(element: HTMLElement): HTMLFormElement {
  const form = element.closest('form')
  if (!form) throw new Error('not inside a form')
  return form
}

describe('checkout page (D-CHECKOUT-UI-001 layout, AUTHN-001 §6.2 values only)', () => {
  it('shows the order summary from server values only: site host, order name and the amount', () => {
    render(<App data={pageData()} />)
    expect(screen.getByRole('heading', { level: 1, name: '주문 내용을 확인해 주세요.' })).toBeInTheDocument()
    const summary = screen.getByRole('region', { name: '주문 정보' })
    expect(within(summary).getByText('shop.example.test')).toBeInTheDocument()
    expect(within(summary).getByText('Demo order')).toBeInTheDocument()
    expect(within(summary).getByText('총 결제 금액').nextElementSibling).toHaveTextContent('10,000원')
    expect(screen.getByText('PayCore', { selector: '.brand-name' })).toBeInTheDocument()
  })

  it('a missing order name omits that row instead of inventing one', () => {
    render(<App data={pageData({ orderName: null })} />)
    const summary = screen.getByRole('region', { name: '주문 정보' })
    expect(within(summary).queryByText('주문명')).toBeNull()
  })

  it('merchant-supplied text is rendered as text, never as markup', () => {
    const { container } = render(<App data={pageData({ orderName: '<img src=x onerror=alert(1)><script>alert(2)</script>' })} />)
    expect(screen.getByText('<img src=x onerror=alert(1)><script>alert(2)</script>')).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
  })

  it('card companies are an accessible radio group with no preselection; the pay button stays disabled until one is chosen', async () => {
    render(<App data={pageData()} />)
    const group = screen.getByRole('group', { name: '카드사 선택' })
    const radios = within(group).getAllByRole('radio')
    expect(radios.map((r) => (r as HTMLInputElement).value)).toEqual(['SIMISSA', 'SIMISSB'])
    radios.forEach((r) => expect(r).not.toBeChecked())
    const pay = screen.getByRole('button', { name: '10,000원 결제하기' })
    expect(pay).toBeDisabled()
    expect(screen.getByText('카드사를 선택해 주세요')).toBeInTheDocument()

    await userEvent.click(within(group).getByRole('radio', { name: '가상카드B' }))
    expect(within(group).getByRole('radio', { name: '가상카드B' })).toBeChecked()
    expect(pay).toBeEnabled()
    expect(screen.getByText('카드사 인증 화면으로 이동해요')).toBeInTheDocument()
  })

  it('installments are a select of the server plans with the first one selected', () => {
    render(<App data={pageData()} />)
    const select = screen.getByRole('combobox', { name: '할부 선택' }) as HTMLSelectElement
    expect(select.name).toBe('plan')
    expect(Array.from(select.options).map((o) => [o.value, o.text])).toEqual([['LUMP_SUM', '일시불'], ['INSTALLMENT_3', '3개월 할부']])
    expect(select.value).toBe('LUMP_SUM')
  })

  it('the pay form is a same-origin POST with exactly cardCompany, plan and the CSRF field (no fetch, no card data fields)', async () => {
    const { container } = render(<App data={pageData()} />)
    await userEvent.click(screen.getByRole('radio', { name: '가상카드A' }))
    const form = formOf(screen.getByRole('button', { name: '10,000원 결제하기' }))
    expect(form.getAttribute('method')).toBe('post')
    expect(form.getAttribute('action')).toBe(`/checkout/s/${HANDLE}/authentication`)
    const fields = Array.from(new FormData(form).entries())
    expect(fields).toEqual([['_csrf', 'masked-csrf-token'], ['cardCompany', 'SIMISSA'], ['plan', 'LUMP_SUM']])
    // 카드번호·유효기간·CVC·비밀번호 같은 입력은 결제창에 없다(인증은 Simulator 화면).
    expect(container.querySelectorAll('input:not([type=radio]):not([type=hidden]), textarea')).toHaveLength(0)
    expect(container.querySelectorAll('[autocomplete^="cc-"], input[type=password]')).toHaveLength(0)
  })

  it('the close button asks first; confirming submits the existing cancel POST with the CSRF field, keeping it returns to the page', async () => {
    render(<App data={pageData()} />)
    expect(screen.queryByRole('button', { name: '결제 취소' })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: '결제창 닫기' }))
    const dialog = screen.getByRole('alertdialog', { name: '결제를 취소할까요?' })
    const confirm = within(dialog).getByRole('button', { name: '결제 취소' })
    const form = formOf(confirm)
    expect(form.getAttribute('method')).toBe('post')
    expect(form.getAttribute('action')).toBe(`/checkout/s/${HANDLE}/cancel`)
    expect(Array.from(new FormData(form).entries())).toEqual([['_csrf', 'masked-csrf-token']])
    await userEvent.click(within(dialog).getByRole('button', { name: '계속 결제' }))
    expect(screen.queryByRole('alertdialog')).toBeNull()
  })

  it('shows the three-step indicator with the first step current', () => {
    render(<App data={pageData()} />)
    const steps = screen.getByRole('list', { name: '결제 단계' })
    expect(within(steps).getAllByRole('listitem').map((li) => li.textContent)).toEqual(['1카드 선택', '2카드사 인증', '3결제 완료'])
    expect(within(steps).getByText('카드 선택').closest('li')).toHaveAttribute('aria-current', 'step')
  })

  it('a server notice (422) is announced as an alert', () => {
    render(<App data={pageData({ notice: '선택한 카드사 또는 할부를 사용할 수 없습니다.' })} />)
    expect(screen.getByRole('alert')).toHaveTextContent('선택한 카드사 또는 할부를 사용할 수 없습니다.')
  })

  it('a non-KRW amount keeps the server amount with its currency code', () => {
    render(<App data={pageData({ amountDisplay: '10.50', currency: 'USD' })} />)
    expect(screen.getByRole('button', { name: '10.50 USD 결제하기' })).toBeDisabled()
  })

  it('does not show prototype sample content or terms checkboxes (CHECKOUT-TERMS-001)', () => {
    const { container } = render(<App data={pageData()} />)
    for (const text of ['fingerpay', '프로토타입', '시나리오', '약관', '배송비', 'ORD-DEMO']) {
      expect(container.textContent).not.toContain(text)
    }
    expect(container.querySelectorAll('input[type=checkbox]')).toHaveLength(0)
  })
})
