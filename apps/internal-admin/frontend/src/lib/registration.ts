/** 화면 등록 공용 값(MERCHANT-OPS-002 §4.2·C-7) — 가맹점 등록 화면·조회 상세 Revision 폼이 같이 쓴다. */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

/** [at]의 Asia/Seoul 날짜 `YYYY-MM-DD`(KST는 일광절약시간 없음 — UTC+9 고정). 시작일 선택기 최솟값(안내)에 쓴다. */
export const kstDate = (at: Date) => new Date(at.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10)

/** 가맹점명·옛 사업자명 공통 이름 규칙(1~100 code point, 앞뒤 공백·제어문자 없음 — MASTER DisplayName). BIZ-4: 옛 BusinessRegistrationScreen에서 옮김(C-27). */
export const validName = (value: string) => value.length > 0 && [...value].length <= 100 && value === value.trim() && !/\p{Cc}/u.test(value)

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

/**
 * 소속 가맹점(MERCHANT-CODE-001 MC-2b — D-MCODE-003 ①): 화면은 "소속 가맹점: <상호> · <가맹점 코드>"로만 가맹점을 보인다.
 * [merchantId](mer_…)는 **화면에 그리지 않는** 내부 키 — Site·계약 초안 저장 호출 본문에만 그대로 들어간다(API·감사 불변).
 */
export interface MerchantRef {
  merchantId: string
  /** 그 가맹점의 사업자 내부 키(biz_… — 화면에 그리지 않음). 계약 당사자와 같은 사업자인지 화면에서 먼저 본다(MC-2b 리뷰 MINOR-2 — 판정은 MASTER). */
  businessId: string
  name: string
  code: string
}
