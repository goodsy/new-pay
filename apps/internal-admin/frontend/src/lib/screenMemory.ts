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
  }
}

/** `useState`와 같지만 값은 [memory]의 [key]에 둔다. [initial]은 매 렌더 같은 참조여야 한다(객체면 모듈 상수). */
export function useRemembered<T>(memory: ScreenMemory, key: string, initial: T): [T, (next: T) => void] {
  const value = useSyncExternalStore(memory.subscribe, () => (memory.has(key) ? (memory.read(key) as T) : initial))
  const set = useCallback((next: T) => memory.write(key, next), [memory, key])
  return [value, set]
}
