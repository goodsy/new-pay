// 시험 전용 최소 타입(@types/node 의존성 없이 styles.css 원문을 읽는다 — ADMIN-UI-001 stylesheet 정책 시험).
declare module 'node:fs' {
  export function readFileSync(path: string, encoding: 'utf8'): string
}
