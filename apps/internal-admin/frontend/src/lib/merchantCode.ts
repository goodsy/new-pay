/**
 * 가맹점 코드(MERCHANT-CODE-001 §3.1·§3.4 — MC-2 화면 미리보기). 미리보기는 **제안일 뿐**이다: 사용 가능 여부(중복·은퇴 코드·예약어)는 등록 시 MASTER가 판정한다
 * (`MERCHANT_CODE_TAKEN`·`MERCHANT_CODE_RESERVED` — 사전 조회·예약 없음, C-4). 운영자가 고친 값은 그대로 보낸다(정규화 없음).
 *
 * 제안 규칙(§3.4): "영문 상호"(저장 안 함, 음역 없음 — C-5) → 영문·숫자만 남겨 소문자화 → 첫 글자 영문까지 앞을 자름 → 최대 10자 + 랜덤 4자, 합이 6자 미만이면 랜덤으로 보충.
 * 랜덤 문자는 혼동 문자 `0 o 1 l i`를 뺀 31자(C-3), 소스는 `crypto.getRandomValues`(예측 가능한 난수 금지 — MC-2-4 변이 대상).
 */

/** §3.1 형식(MASTER `MerchantCode.FORMAT`과 같은 규칙 — 정규화 없음). */
export const MERCHANT_CODE = /^[a-z][a-z0-9]{5,13}$/
export const MERCHANT_CODE_HINT = '영문 소문자로 시작, 영문 소문자·숫자 6~14자'

/** C-3 랜덤 문자 31자(`0 o 1 l i` 제외). 첫 글자 자리에 쓸 때는 영문 23자만. */
export const RANDOM_ALPHABET = 'abcdefghjkmnpqrstuvwxyz23456789'
const RANDOM_LETTERS = 'abcdefghjkmnpqrstuvwxyz'
/** 상호에서 가져오는 앞부분 최대 길이와 랜덤 꼬리 길이(§3.4). */
export const PREFIX_MAX = 10
export const TAIL_LENGTH = 4
const MIN_LENGTH = 6
/** 꼬리 저장 길이: 상호가 비어도 6자를 채울 수 있게 6자(첫 글자는 영문). */
const STORED_TAIL = MIN_LENGTH

/** 난수 바이트 소스(시험 주입용 — 기본은 Web Crypto). */
export type RandomBytes = (length: number) => Uint8Array
export const cryptoRandomBytes: RandomBytes = (length) => crypto.getRandomValues(new Uint8Array(length))

/** 상호에서 코드 앞부분: 영문·숫자만 소문자화 → 첫 영문까지 자름 → 최대 10자. 영문이 없으면 빈 문자열. */
export function codePrefix(englishName: string): string {
  const kept = englishName.replace(/[^A-Za-z0-9]/g, '').toLowerCase()
  const start = kept.search(/[a-z]/)
  return start < 0 ? '' : kept.slice(start, start + PREFIX_MAX)
}

/** 균등 추출(편향 없는 거부 표본 — [alphabet] 길이의 배수 밖 바이트는 버리고 한 바이트씩 다시 뽑는다). */
function pick(alphabet: string, random: RandomBytes): string {
  const limit = 256 - (256 % alphabet.length)
  for (;;) {
    const byte = random(1)[0]!
    if (byte < limit) return alphabet[byte % alphabet.length]!
  }
}

/** 새 랜덤 꼬리(6자 — 첫 글자 영문, 나머지 31자 중). "다시 제안"마다 새로 만든다. */
export function randomTail(random: RandomBytes = cryptoRandomBytes): string {
  let tail = pick(RANDOM_LETTERS, random)
  while (tail.length < STORED_TAIL) tail += pick(RANDOM_ALPHABET, random)
  return tail
}

/**
 * 상호 + 저장된 꼬리 → 제안 코드(상호를 고쳐도 꼬리는 그대로 — 입력할 때마다 랜덤이 바뀌지 않는다).
 * 꼬리 첫 글자(영문 23자 중)는 **앞부분이 빈 경우에만** 쓴다(코드 첫 글자가 영문이어야 하므로). 앞부분이 있으면 꼬리 둘째 글자부터 — 랜덤 4자(또는 보충분) 모두 31자 중.
 */
export function composeMerchantCode(englishName: string, tail: string): string {
  const prefix = codePrefix(englishName)
  if (prefix === '') return tail.slice(0, MIN_LENGTH)
  return prefix + tail.slice(1, 1 + Math.max(TAIL_LENGTH, MIN_LENGTH - prefix.length))
}

/** 한 번에 제안(상호 + 새 랜덤 꼬리). */
export function suggestMerchantCode(englishName: string, random: RandomBytes = cryptoRandomBytes): string {
  return composeMerchantCode(englishName, randomTail(random))
}
