import type { InputProblem } from '../api/client'

/** I-111: 서버가 돌려주는 입력 거절 필드·이유(고정 code). 이 집합 밖 값은 버린다(서버 값·입력 원문을 화면에 쓰지 않는다). */
export const INPUT_FIELDS: Record<string, string> = {
  paymentId: '결제 ID',
  amountMinor: '취소 금액',
  purposeKind: '목적 종류',
  purposeReference: '근거 참조',
  reason: '사유',
  // ACQ-ADMIN-001 B1-A(재매입 요청 — 리뷰 M-1: 근거 참조 카드 번호 패턴 거절 등을 필드별로 보인다).
  evidenceRef: '근거 참조',
  reasonCode: '사유 코드',
}

export const INPUT_REASONS: Record<string, string> = {
  FORMAT: '형식이 올바르지 않습니다.',
  NOT_POSITIVE: '0보다 큰 정수여야 합니다.',
  UNKNOWN_KIND: '정해진 목록에서 골라 주세요.',
  LENGTH: '길이가 허용 범위(근거 1~200자, 사유 1~500자)를 벗어났습니다.',
  MALFORMED_TEXT: '깨진 문자가 있습니다.',
  EDGE_WHITESPACE: '앞뒤에 공백이나 줄바꿈이 있습니다.',
  CHARACTER_NOT_ALLOWED: '허용되지 않는 문자가 있습니다(근거: 문자·숫자·공백·- _ . : / # 만, 사유: 줄바꿈 외 제어 문자 불가).',
  CARD_NUMBER_PATTERN:
    '카드 번호처럼 보이는 13~19자리 숫자가 있어 거부되었습니다. 날짜·일련번호가 이어진 참조(날짜 8자리 + 일련번호 등)도 이 규칙에 걸립니다 — 숫자 묶음 사이에 구분 기호를 더 넣어 주세요.',
}

export function parseInputProblem(body: unknown): InputProblem | undefined {
  if (typeof body !== 'object' || body === null) return undefined
  const { field, reason } = body as { field?: unknown; reason?: unknown }
  if (typeof field !== 'string' || typeof reason !== 'string') return undefined
  if (!Object.prototype.hasOwnProperty.call(INPUT_FIELDS, field) || !Object.prototype.hasOwnProperty.call(INPUT_REASONS, reason)) return undefined
  return { field, reason }
}

export function describeInputProblem(problem: InputProblem): string {
  return `${INPUT_FIELDS[problem.field] ?? ''}: ${INPUT_REASONS[problem.reason] ?? ''}`
}
