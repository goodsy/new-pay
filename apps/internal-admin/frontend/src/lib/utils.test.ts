// @vitest-environment node
// 순수 함수 시험 — jsdom 환경을 만들지 않는다(시험 파일마다 드는 환경 비용 절약).
import { describe, expect, it } from 'vitest'
import { cn } from './utils'

describe('cn (shadcn class helper)', () => {
  it('drops falsy parts and lets a later Tailwind class win over a conflicting earlier one', () => {
    expect(cn('px-2 py-1', false && 'hidden', undefined, 'px-4')).toBe('py-1 px-4')
  })
})
