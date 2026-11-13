/** 화면 등록 공용 값(MERCHANT-OPS-002 §4.2·C-7) — 가맹점 등록 화면·조회 상세 Revision 폼이 같이 쓴다. */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

/** [at]의 Asia/Seoul 날짜 `YYYY-MM-DD`(KST는 일광절약시간 없음 — UTC+9 고정). 시작일 선택기 최솟값(안내)에 쓴다. */
export const kstDate = (at: Date) => new Date(at.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10)

/** 폼 제출 단위 submissionId(UUID v4 소문자 hex 32자 — 하이픈 제거, §4.2). */
export const defaultSubmissionId = () => globalThis.crypto.randomUUID().replace(/-/g, '')

/** 화면이 입력값에서 계산한 마스킹 번호(앞 3자리 + `*******` — 서버 마스킹 값과 같은 모양, D-MD-008). 평문은 화면에 남기지 않는다. */
export const maskRegistrationNumber = (value: string) => `${value.slice(0, 3)}*******`

/**
 * 사업자 등록 화면 → 가맹점 등록 넘겨주기 전 가맹점 등록 마법사 상태(ADMIN-UI-MERCHANT-002 리뷰 MINOR-1):
 * locked = 결과 확인이 필요한 카드가 있음(넘기지 않는다), inProgress = 진행 내용이 있음(새로 시작할지 확인), empty = 비어 있음.
 */
export type HandoffState = 'locked' | 'inProgress' | 'empty'

/**
 * 고른 사업자(ADMIN-UI-MERCHANT-002, D-ADMIN-UI-004 ③): 화면은 상호·마스킹 등록번호로만 사업자를 보인다.
 * [businessId](biz_…)는 **화면에 그리지 않는** 내부 키 — 가맹점·계약 초안 저장 호출 본문에만 그대로 들어간다(API·감사 불변).
 */
export interface BusinessRef {
  businessId: string
  name: string
  masked: string
}
