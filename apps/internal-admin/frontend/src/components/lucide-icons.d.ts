// lucide-react 개별 아이콘 모듈 타입(패키지는 진입점 타입만 제공한다 — icons.ts 참조).
declare module 'lucide-react/dist/esm/icons/*.mjs' {
  import type { LucideIcon } from 'lucide-react'
  const icon: LucideIcon
  export default icon
}
