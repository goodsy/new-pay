import type { ContractIntakeView, ContractTransitionView } from '../api/adminApi'

/**
 * 사업자 상세 '계약 진행' 섹션 표시 규칙(MERCHANT-FLOW-001 §4A.3·§4A.6·§4A.9 — FLOW-1 읽기 전용).
 * - '계약 완료'는 영업 기록이다. 가맹점 계약 승인(결제 허용·MID 발급 근거)과 다른 말로 둔다(R-1) — 상태 이름에 '승인'을 쓰지 않는다.
 * - 이관 건(§4A.9)은 근거·담당자·접수 항목 칸을 '이관'으로 그린다(R-6). 이관 완료 건 요약 = "계약 완료(이관) · 근거: 이관".
 */
export const CONTRACT_STATUS_LABELS: Readonly<Record<string, string>> = {
  CONSULTING: '상담 중',
  DOCUMENT_RECEIVED: '계약서 접수',
  COMPLETED: '계약 완료',
  REJECTED: '계약 불가',
  TERMINATED: '계약 해지',
}
export const INTAKE_CHANNEL_LABELS: Readonly<Record<string, string>> = {
  DIRECT_SALES: '직접 영업',
  PHONE: '전화',
  WEBSITE: '홈페이지',
  SALES_AGENCY: '영업대행사',
  OTHER: '기타',
}
export const DESIRED_SERVICE_LABELS: Readonly<Record<string, string>> = { CARD_AUTH: '카드 인증 결제', CARD_KEYIN: '카드 키인 결제' }

export const MIGRATED = '이관'
/** 계정을 찾지 못한 관리자 처리자(원문 키를 대신 보이지 않는다 — m-11). */
export const UNKNOWN_ACTOR = '확인 불가'
/** §4A.6 [구현 관례 문구]. */
export const DESIRED_SERVICE_NOTE = '희망 서비스 — 참고용, 실제 서비스는 가맹점 계약 승인·개통에서 정합니다'

const label = (labels: Readonly<Record<string, string>>, code: string) => labels[code] ?? code

/** 계약서 수령 근거가 있는(계약 완료를 거친) 상태. */
const CONTRACTED = new Set(['COMPLETED', 'TERMINATED'])

/** 접수 건 요약 줄: 상태(이관 완료 = '계약 완료(이관)') + 계약을 거친 건이면 " · 근거: …"(이관 건 = '이관', 아니면 계약 완료 전이의 근거 참조 원문). */
export function intakeSummary(intake: ContractIntakeView): string {
  const status = intake.migrated && intake.status === 'COMPLETED' ? `${CONTRACT_STATUS_LABELS.COMPLETED}(${MIGRATED})` : label(CONTRACT_STATUS_LABELS, intake.status)
  if (!intake.migrated && !CONTRACTED.has(intake.status)) return status
  const evidence = intake.migrated ? MIGRATED : (intake.transitions.find((t) => t.toStatus === 'COMPLETED')?.documentRef ?? '-')
  return `${status} · 근거: ${evidence}`
}

export function transitionChange(t: ContractTransitionView): string {
  const from = t.fromStatus === null ? (t.migrated ? MIGRATED : '접수') : label(CONTRACT_STATUS_LABELS, t.fromStatus)
  return `${from} → ${label(CONTRACT_STATUS_LABELS, t.toStatus)}`
}

export const transitionEvidence = (t: ContractTransitionView): string => (t.migrated ? MIGRATED : (t.documentRef ?? '-'))

export function transitionActor(t: ContractTransitionView): string {
  if (t.actorKind === 'SYSTEM') return t.migrated ? MIGRATED : '시스템'
  return t.actorName ?? UNKNOWN_ACTOR
}

export const intakeChannel = (intake: ContractIntakeView): string =>
  intake.migrated ? MIGRATED : intake.intakeChannel === null ? '-' : label(INTAKE_CHANNEL_LABELS, intake.intakeChannel)

export const contractManager = (intake: ContractIntakeView): string => (intake.migrated ? MIGRATED : (intake.contractManagerName ?? UNKNOWN_ACTOR))

export function desiredServices(intake: ContractIntakeView): string {
  if (intake.migrated) return MIGRATED
  return intake.desiredServices.length === 0 ? '선택 없음' : intake.desiredServices.map((s) => label(DESIRED_SERVICE_LABELS, s)).join(', ')
}
