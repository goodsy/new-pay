export type Tone = 'green' | 'orange' | 'blue' | 'red' | 'default';

/**
 * 상태 문자열 → 태그 색상 (원본 badge() 규칙)
 * - 완료 | 운영 중            → green
 * - 보류 | 반송 | 차이 | 필요   → orange
 * - 그 외                    → blue
 * red 는 정의만 되어 있고 원본에서 자동 매핑되지 않는다.
 */
export function statusTone(status: string): Tone {
  if (/완료|운영 중/.test(status)) return 'green';
  if (/보류|반송|차이|필요/.test(status)) return 'orange';
  return 'blue';
}
