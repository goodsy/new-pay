/** 화면 등록 공용 값(MERCHANT-OPS-002 §4.2·C-7) — 가맹점 등록 화면·조회 상세 Revision 폼이 같이 쓴다. */

const KST_OFFSET_MS = 9 * 60 * 60 * 1000

/** [at]의 Asia/Seoul 날짜 `YYYY-MM-DD`(KST는 일광절약시간 없음 — UTC+9 고정). 시작일 선택기 최솟값(안내)에 쓴다. */
export const kstDate = (at: Date) => new Date(at.getTime() + KST_OFFSET_MS).toISOString().slice(0, 10)

/** 폼 제출 단위 submissionId(UUID v4 소문자 hex 32자 — 하이픈 제거, §4.2). */
export const defaultSubmissionId = () => globalThis.crypto.randomUUID().replace(/-/g, '')
