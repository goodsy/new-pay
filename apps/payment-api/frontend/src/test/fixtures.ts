import type { ErrorData, PageData } from '../checkoutData'

/** 시험용 결제창 데이터(서버 shell `data-checkout`과 같은 모양). 값은 시험 대역이다 — 운영 값·실제 카드사 아님. */
export const HANDLE = 'AbCdEfGhIjKlMnOpQrStUv'

export function pageData(overrides: Partial<PageData> = {}): PageData {
  return {
    screen: 'page',
    orderName: 'Demo order',
    amountDisplay: '10,000',
    currency: 'KRW',
    siteHost: 'shop.example.test',
    cardCompanies: [
      { value: 'SIMISSA', label: '가상카드A' },
      { value: 'SIMISSB', label: '가상카드B' },
    ],
    plans: [
      { value: 'LUMP_SUM', label: '일시불' },
      { value: 'INSTALLMENT_3', label: '3개월 할부' },
    ],
    notice: null,
    authenticationAction: `/checkout/s/${HANDLE}/authentication`,
    cancelAction: `/checkout/s/${HANDLE}/cancel`,
    csrfParameter: '_csrf',
    csrfToken: 'masked-csrf-token',
    ...overrides,
  }
}

export function errorData(overrides: Partial<ErrorData> = {}): ErrorData {
  return {
    screen: 'error',
    message: '인증이 완료되지 않았습니다.',
    retryUrl: `/checkout/s/${HANDLE}`,
    failRedirect: 'https://shop.example.test/fail?paymentId=pay_1&code=AUTHENTICATION_FAILED',
    ...overrides,
  }
}
