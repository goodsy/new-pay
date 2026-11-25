/**
 * 사업자 명령 400 `INVALID_INPUT`의 필드 오류(BUSINESS-PROFILE-001 §4.6·§7.3 — path·code만, 값 없음).
 * 서버 응답의 path 문법·code 집합 밖 항목이 하나라도 있으면 **전부 버린다**(서버 값·입력 원문을 화면에 쓰지 않는다 — 일반 입력 오류 문구만 남는다).
 */
export interface FieldError {
  path: string
  code: string
}

/** §4.6 code 집합(MASTER `BusinessFieldError` 그대로). */
export const FIELD_ERROR_CODES = [
  'REQUIRED', 'FORMAT', 'TOO_LONG', 'TOO_MANY', 'DUPLICATE', 'NOT_ALLOWED', 'SOLE_WITH_OTHERS', 'PRIMARY_REQUIRED', 'PRIMARY_MULTIPLE',
  'ACTUAL_SAME_AS_REGISTERED', 'BUSINESS_TYPE_ITEM_REQUIRED',
  // SEC-BIZ-001 M-1(BR-BIZ-305): '사유 및 비고' 카드번호 패턴.
  'CARD_NUMBER_PATTERN',
] as const

/** §4.6 path 문법(i = 0부터, 상한 안 정수 — 화면은 두 자리까지만 믿는다). */
const PATH = new RegExp(
  '^(businessType|registrationNumber|displayName|reason|version|representatives|categories|addresses|contacts' +
    '|registration\.(corporateRegistrationNumber|openingDate|countryCode|englishName|phoneNumber|email)' +
    '|representatives\[[0-9]{1,2}\]\.(name|type)' +
    '|categories\[[0-9]{1,2}\]\.(type|name)' +
    '|addresses\[[0-9]{1,2}\]\.(type|postalCode|baseAddress|detailAddress)' +
    '|contacts\[[0-9]{1,2}\]\.(name|email|phoneNumber|primary))$',
)

const MAX_FIELD_ERRORS = 50

export function parseFieldErrors(body: unknown): FieldError[] | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const list = (body as { fieldErrors?: unknown }).fieldErrors
  if (!Array.isArray(list) || list.length === 0 || list.length > MAX_FIELD_ERRORS) return undefined
  const parsed: FieldError[] = []
  for (const item of list) {
    if (typeof item !== 'object' || item === null) return undefined
    const { path, code } = item as { path?: unknown; code?: unknown }
    if (typeof path !== 'string' || typeof code !== 'string' || !PATH.test(path) || !(FIELD_ERROR_CODES as readonly string[]).includes(code)) return undefined
    parsed.push({ path, code })
  }
  return parsed
}
