import { useCallback, useSyncExternalStore } from 'react'

/**
 * 화면 하나의 상태를 그 화면 밖(App)에 메모리로만 두는 작은 저장소(브라우저 저장소·URL 없음).
 * 탭을 바꿔 화면이 사라졌다가 다시 그려져도 같은 값을 읽는다 — 화면이 사라진 동안 도착한 응답도 여기에 쓰이고, 다시 그린 화면이 구독해 반영한다.
 */
export interface ScreenMemory {
  read(key: string): unknown
  has(key: string): boolean
  write(key: string, value: unknown): void
  subscribe(listener: () => void): () => void
  /** 지금 있는 키(MERCHANT-OPS-002 B2-1 리뷰 n-a — 세션 경계에서 결과 확인이 필요한 폼이 있었는지 본다). */
  keys(): string[]
  /** 키를 지운다(ADMIN-UI-MERCHANT-001 "새 사업자 등록 시작" — 지운 키는 [useRemembered]가 초기값으로 읽는다). */
  remove(key: string): void
}

export function createScreenMemory(): ScreenMemory {
  const values = new Map<string, unknown>()
  const listeners = new Set<() => void>()
  return {
    read: (key) => values.get(key),
    has: (key) => values.has(key),
    write: (key, value) => {
      if (values.has(key) && Object.is(values.get(key), value)) return
      values.set(key, value)
      for (const listener of [...listeners]) listener()
    },
    subscribe: (listener) => {
      listeners.add(listener)
      return () => listeners.delete(listener)
    },
    keys: () => [...values.keys()],
    remove: (key) => {
      if (!values.delete(key)) return
      for (const listener of [...listeners]) listener()
    },
  }
}

/** `useState`와 같지만 값은 [memory]의 [key]에 둔다. [initial]은 매 렌더 같은 참조여야 한다(객체면 모듈 상수). */
export function useRemembered<T>(memory: ScreenMemory, key: string, initial: T): [T, (next: T) => void] {
  const value = useSyncExternalStore(memory.subscribe, () => (memory.has(key) ? (memory.read(key) as T) : initial))
  const set = useCallback((next: T) => memory.write(key, next), [memory, key])
  return [value, set]
}

/**
 * 결과 확인이 필요한 폼이 있는지(MERCHANT-OPS-002 B2-1 리뷰 n-a): 등록 단계·Revision 폼의 상태 키(`….status`)가 보내는 중(`busy`)이거나 결과 불명(`unknown`)이면 true.
 * 세션이 끝나면 메모리를 버리므로 App이 버리기 전에 한 번 물어 로그인 화면에 안내한다(submissionId·입력은 남기지 않는다).
 */
export function hasUnresolvedRequest(...memories: ScreenMemory[]): boolean {
  return memories.some((memory) =>
    memory.keys().some((key) => {
      if (!key.endsWith('.status')) return false
      const kind = (memory.read(key) as { kind?: unknown } | undefined)?.kind
      return kind === 'busy' || kind === 'unknown'
    }),
  )
}
