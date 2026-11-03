import { parseCheckoutData, type CheckoutData } from './checkoutData'

/** shell `#root`의 `data-checkout`(서버가 HTML 속성 escape로 넣은 JSON)만 읽는다. 다른 출처(URL·storage·fetch)는 쓰지 않는다. */
export function readCheckoutData(root: HTMLElement | null): CheckoutData {
  return parseCheckoutData(root?.getAttribute('data-checkout'))
}
