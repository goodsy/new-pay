/**
 * 화면이 다루는 통화 목록(MERCHANT-OPS-002 B3-2 — C-41: 관리 채널 통화 활성화는 **KRW만**). `paymentMethods.ts`와 같이 구조만 확장 가능하게 두고,
 * 항목을 더하는 것은 새 통화 범위 정책 채택이라 서버 계약(MASTER parser·ADMIN 입력 규칙)과 사용자 결정 없이 넣지 않는다.
 */
export const CURRENCIES: readonly { code: 'KRW'; label: string }[] = Object.freeze([{ code: 'KRW', label: '원화(KRW)' }])

export type CurrencyCode = (typeof CURRENCIES)[number]['code']

/** 통화 효력 시작 모드(D-MOPS-009 ② — §9.26). */
export type EffectiveFromMode = 'AT_EXECUTION' | 'DATE'

const KST_OFFSET_MS = 9 * 60 * 60 * 1000
const DAY_MS = 24 * 60 * 60 * 1000

/** [now]의 Asia/Seoul 날짜(YYYY-MM-DD — 서머타임 없음, +09:00 고정). */
export const kstDateOf = (now: Date): string => new Date(now.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10)

/** [now] 기준 KST 내일(DATE 모드 최솟값 — 서버도 내일 이후만 받는다, advisory). */
export const kstTomorrowOf = (now: Date): string => kstDateOf(new Date(now.getTime() + DAY_MS))

/** KST 날짜(YYYY-MM-DD) 다음 날. */
export const nextKstDate = (date: string): string => new Date(new Date(`${date}T00:00:00.000Z`).getTime() + DAY_MS).toISOString().slice(0, 10)
