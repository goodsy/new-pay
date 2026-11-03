/**
 * 결제창 화면 데이터(CHECKOUT-REACT-001 §3): 서버가 shell `#root`의 `data-checkout` 속성에 넣은 JSON. 값은 표시 전용이고 판정은 서버 POST 처리가 다시 한다.
 * 형식 검사는 심층 방어다 — 하나라도 어긋나면 고정 문구 화면([FALLBACK])만 그리고 링크·폼을 만들지 않는다.
 */
export type Choice = { value: string; label: string }

export type PageData = {
  screen: 'page'
  orderName: string | null
  amountDisplay: string
  currency: string
  siteHost: string
  cardCompanies: Choice[]
  plans: Choice[]
  notice: string | null
  authenticationAction: string
  cancelAction: string
  csrfParameter: '_csrf'
  csrfToken: string
}

export type ErrorData = {
  screen: 'error'
  message: string
  retryUrl: string | null
  failRedirect: string | null
}

export type CheckoutData = PageData | ErrorData

export const FALLBACK: ErrorData = Object.freeze({ screen: 'error', message: '일시적인 오류입니다.', retryUrl: null, failRedirect: null }) as ErrorData

const HANDLE = '[A-Za-z0-9_-]{22}'
const AUTHENTICATION_ACTION = new RegExp(`^/checkout/s/${HANDLE}/authentication$`)
const CANCEL_ACTION = new RegExp(`^/checkout/s/${HANDLE}/cancel$`)
// 다시 시도 = 결제창 화면 또는 같은 복귀 URL(확인 중 503 — 서버가 resultRef·state만 percent-encode해 다시 만든다, AUTHN-001 §6.3).
// 경로 접두가 같은 출처 결제창 경로로 고정되므로 query는 출력 가능한 ASCII(공백·따옴표·꺾쇠·역슬래시·fragment 제외)만 확인한다.
const RETRY_URL = new RegExp(`^/checkout/s/${HANDLE}(/authentication/return\\?resultRef=[\\x21-\\x7e]*&state=[\\x21-\\x7e]*)?$`)
const RETRY_FORBIDDEN = /["'<>\\#]/

const PAGE_KEYS = [
  'screen', 'orderName', 'amountDisplay', 'currency', 'siteHost', 'cardCompanies', 'plans', 'notice',
  'authenticationAction', 'cancelAction', 'csrfParameter', 'csrfToken',
] as const
const ERROR_KEYS = ['screen', 'message', 'retryUrl', 'failRedirect'] as const

type Json = Record<string, unknown>

const isObject = (v: unknown): v is Json => typeof v === 'object' && v !== null && !Array.isArray(v)
const isString = (v: unknown): v is string => typeof v === 'string'
const isNonEmpty = (v: unknown): v is string => isString(v) && v.length > 0
const isNullableString = (v: unknown): v is string | null => v === null || isString(v)
const hasExactly = (o: Json, keys: readonly string[]) => Object.keys(o).length === keys.length && keys.every((k) => k in o)

const isChoices = (v: unknown): v is Choice[] =>
  Array.isArray(v) && v.every((c) => isObject(c) && hasExactly(c, ['value', 'label']) && isNonEmpty(c.value) && isNonEmpty(c.label))

function isHttpsUrl(v: string): boolean {
  if (!v.startsWith('https://')) return false
  try {
    const url = new URL(v)
    return url.protocol === 'https:' && url.username === '' && url.password === '' && url.hostname.length > 0
  } catch {
    return false
  }
}

function page(o: Json): PageData | null {
  if (!hasExactly(o, PAGE_KEYS)) return null
  const ok =
    isNullableString(o.orderName) && isNonEmpty(o.amountDisplay) && isNonEmpty(o.currency) && isNonEmpty(o.siteHost) &&
    isChoices(o.cardCompanies) && isChoices(o.plans) && isNullableString(o.notice) &&
    isString(o.authenticationAction) && AUTHENTICATION_ACTION.test(o.authenticationAction) &&
    isString(o.cancelAction) && CANCEL_ACTION.test(o.cancelAction) &&
    o.csrfParameter === '_csrf' && isNonEmpty(o.csrfToken)
  return ok ? (o as PageData) : null
}

function error(o: Json): ErrorData | null {
  if (!hasExactly(o, ERROR_KEYS)) return null
  const ok =
    isNonEmpty(o.message) &&
    (o.retryUrl === null || (isString(o.retryUrl) && RETRY_URL.test(o.retryUrl) && !RETRY_FORBIDDEN.test(o.retryUrl))) &&
    (o.failRedirect === null || (isString(o.failRedirect) && isHttpsUrl(o.failRedirect)))
  return ok ? (o as ErrorData) : null
}

export function parseCheckoutData(raw: string | null | undefined): CheckoutData {
  if (!raw) return FALLBACK
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return FALLBACK
  }
  if (!isObject(value)) return FALLBACK
  const parsed = value.screen === 'page' ? page(value) : value.screen === 'error' ? error(value) : null
  return parsed ?? FALLBACK
}

/** 금액 표시(표시 전용 — 서버 서식 그대로, 계산하지 않는다): KRW는 "원", 그 밖은 통화 코드. */
export function amountLabel(amountDisplay: string, currency: string): string {
  return currency === 'KRW' ? `${amountDisplay}원` : `${amountDisplay} ${currency}`
}
