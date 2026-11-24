/**
 * 사업자등록번호 화면 표시(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 — 사용자 Demo 피드백 "사업자번호 하이픈 자동").
 * **하이픈은 화면 표시에만 있다**: 입력란은 `000-00-00000`으로 보이고, 화면 상태·서버로 보내는 값·형식 검사는 기존 그대로 ASCII 숫자 10자리다
 * (MERCHANT-OPS-001 §3.1·MERCHANT-OPS-002 §5 — 서버 계약 불변). 변환 위치 = [RegistrationNumberInput]의 onChange(입력 → 숫자)와 value(숫자 → 표시).
 */

/** 서버 형식(ASCII 숫자 정확히 10자리 — 서버도 다시 검사한다). */
export const REGISTRATION_NUMBER = /^[0-9]{10}$/

export const isRegistrationNumber = (digits: string) => REGISTRATION_NUMBER.test(digits)

/**
 * 입력에서 ASCII 숫자만 남긴다(하이픈·공백·그 밖 문자는 버리고, 전각 숫자는 숫자로 바꾸지 않는다).
 * 11자리 이상이면 **11자리로 남긴다** — 붙여넣은 잘못된 번호가 10자리로 잘려 맞는 번호처럼 보내지지 않게(형식 오류로 보인다).
 */
export function digitsOf(value: string): string {
  return value.replace(/[^0-9]/g, '').slice(0, 11)
}

/** 숫자 → 표시(입력 중에도 점진적으로: `778`, `778-1`, `778-12-3`, `778-12-34560`). 11번째 숫자는 마지막 묶음에 붙는다(형식 오류 표시). */
export function formatRegistrationNumber(digits: string): string {
  if (digits.length <= 3) return digits
  if (digits.length <= 5) return `${digits.slice(0, 3)}-${digits.slice(3)}`
  return `${digits.slice(0, 3)}-${digits.slice(3, 5)}-${digits.slice(5)}`
}

/** 표시 문자열에서 n번째 숫자 바로 뒤 위치(커서 복원용). n = 0이면 0. */
export function caretAfterDigits(formatted: string, n: number): number {
  if (n <= 0) return 0
  let seen = 0
  for (let i = 0; i < formatted.length; i += 1) {
    if (/[0-9]/.test(formatted[i]!)) seen += 1
    if (seen === n) return i + 1
  }
  return formatted.length
}

/** 표시 문자열의 [end] 앞에 있는 숫자 수. */
export const digitsBefore = (text: string, end: number) => text.slice(0, end).replace(/[^0-9]/g, '').length

/**
 * 조회 응답 번호의 화면 표시(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①): 뉴피지 관리자 화면은 원문을 하이픈 표시(`123-45-67890`)로 보인다.
 * 응답 필드 `registrationNumber`(원문 10자리 — v2 계약)를 받아 표시만 바꾼다. 10 ASCII 숫자가 아니면 손대지 않는다(형식 검사는 ADMIN 응답 모양).
 * 번호는 식별자가 아니다(D-BIZLOOKUP-001) — URL·브라우저 저장소에 두지 않는다. 감사·로그는 서버가 마스킹한다.
 */
export const displayRegistrationNumber = (value: string) => (isRegistrationNumber(value) ? formatRegistrationNumber(value) : value)
