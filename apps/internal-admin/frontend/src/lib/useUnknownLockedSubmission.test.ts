import { describe, expect, it, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'
import { createScreenMemory, hasUnresolvedRequest, type ScreenMemory } from './screenMemory'
import { pendingSubmissionOf, sendUnknownLocked, submissionStatusOf, UNKNOWN_CODES, useUnknownLockedSubmission } from './useUnknownLockedSubmission'
import { apiError } from '../test/fakeApi'

/**
 * LOCK-UNIFY-001 리뷰 MINOR-2: 공용 결과 불명 잠금(MERCHANT-OPS-002 §4.2·§5.3)의 분류 규칙을 직접 고정한다.
 * 결과는 200(결정) 또는 409로만 정해진다 — 결과 불명이면 같은 submissionId·같은 본문만 재전송, 재전송 중 409가 아닌 오류는 잠금 유지.
 */
type Pending = { submissionId: string; body: string }
const KEY = 'step'
const outcome = { outcome: 'SUCCEEDED' as const, reference: 'ref_1' }
const statusOf = (memory: ScreenMemory) => submissionStatusOf<typeof outcome>(memory, KEY)
const pendingOf = (memory: ScreenMemory) => pendingSubmissionOf<Pending>(memory, KEY)
const id = (n: number) => `${n}`.padStart(32, 'a')

describe('sendUnknownLocked', () => {
  it('UNKNOWN_CODES is the single shared list (incl. SERVER_ERROR)', () => {
    expect(UNKNOWN_CODES).toEqual(['MASTER_OUTCOME_UNKNOWN', 'MASTER_RETRY_SAME_KEY', 'AUDIT_UNAVAILABLE', 'SERVER_ERROR'])
  })

  it('(a) a session-ending code with a handler calls the handler and leaves the status busy (seen at the session boundary)', async () => {
    const memory = createScreenMemory()
    const onSessionEnded = vi.fn()
    const sent = { submissionId: id(1), body: 'x' }
    const result = await sendUnknownLocked(memory, KEY, sent, false, () => Promise.reject(apiError(401, 'SESSION_EXPIRED')), onSessionEnded)
    expect(result).toEqual({ kind: 'session' })
    expect(onSessionEnded).toHaveBeenCalledWith('SESSION_EXPIRED')
    expect(statusOf(memory)).toEqual({ kind: 'busy' })
    expect(pendingOf(memory)).toEqual(sent)
    expect(hasUnresolvedRequest(memory)).toBe(true)
  })

  it('(b) without a handler, a session-ending code on a first submit is a decided failure and clears pending', async () => {
    const memory = createScreenMemory()
    const result = await sendUnknownLocked(memory, KEY, { submissionId: id(2), body: 'x' }, false, () => Promise.reject(apiError(401, 'SESSION_REPLACED')))
    expect(result.kind).toBe('failed')
    expect(statusOf(memory)).toEqual({ kind: 'failed', code: 'SESSION_REPLACED' })
    expect(pendingOf(memory)).toBeNull()
    expect(hasUnresolvedRequest(memory)).toBe(false)
  })

  it('(c) without a handler, a session-ending code on a resend keeps the lock and the same pending', async () => {
    const memory = createScreenMemory()
    const sent = { submissionId: id(3), body: 'x' }
    await sendUnknownLocked(memory, KEY, sent, false, () => Promise.reject(apiError(503, 'MASTER_OUTCOME_UNKNOWN')))
    const result = await sendUnknownLocked(memory, KEY, pendingOf(memory)!, true, () => Promise.reject(apiError(401, 'SESSION_EXPIRED')))
    expect(result).toEqual({ kind: 'unknown', code: 'SESSION_EXPIRED' })
    expect(statusOf(memory)).toEqual({ kind: 'unknown', code: 'SESSION_EXPIRED' })
    expect(pendingOf(memory)).toEqual(sent)
  })

  it('(d) a first-submit SERVER_ERROR locks as unknown and the resend carries the same submissionId and body; 200 decides it', async () => {
    const memory = createScreenMemory()
    const call = vi.fn().mockRejectedValueOnce(apiError(500, 'SERVER_ERROR')).mockResolvedValueOnce(outcome)
    await sendUnknownLocked(memory, KEY, { submissionId: id(4), body: 'first' }, false, call)
    expect(statusOf(memory)).toEqual({ kind: 'unknown', code: 'SERVER_ERROR' })
    expect(hasUnresolvedRequest(memory)).toBe(true)
    const result = await sendUnknownLocked(memory, KEY, pendingOf(memory)!, true, call)
    expect(call.mock.calls.map((c) => c[0])).toEqual([{ submissionId: id(4), body: 'first' }, { submissionId: id(4), body: 'first' }])
    expect(result).toEqual({ kind: 'decided', outcome })
    expect(statusOf(memory)).toEqual({ kind: 'decided', outcome })
    expect(pendingOf(memory)).toBeNull()
    expect(hasUnresolvedRequest(memory)).toBe(false)
  })

  it.each([
    [429, 'RATE_LIMITED'],
    [503, 'MASTER_BUSY'],
    [403, 'FORBIDDEN'],
  ] as const)('(e) a resend failing with %i %s keeps the lock and the same pending', async (status, code) => {
    const memory = createScreenMemory()
    const sent = { submissionId: id(5), body: 'x' }
    await sendUnknownLocked(memory, KEY, sent, false, () => Promise.reject(apiError(503, 'MASTER_RETRY_SAME_KEY')))
    await sendUnknownLocked(memory, KEY, pendingOf(memory)!, true, () => Promise.reject(apiError(status, code)))
    expect(statusOf(memory)).toEqual({ kind: 'unknown', code })
    expect(pendingOf(memory)).toEqual(sent)
  })

  it('(e) the same codes on a first submit are decided failures (MASTER not called) — no lock', async () => {
    const memory = createScreenMemory()
    await sendUnknownLocked(memory, KEY, { submissionId: id(6), body: 'x' }, false, () => Promise.reject(apiError(503, 'MASTER_BUSY')))
    expect(statusOf(memory)).toEqual({ kind: 'failed', code: 'MASTER_BUSY' })
    expect(pendingOf(memory)).toBeNull()
  })

  it('(e) a resend failing with 409 IDEMPOTENCY_KEY_REUSED (defect signal) ends the lock; the next submit uses a new submissionId', async () => {
    const memory = createScreenMemory()
    let n = 10
    const { result } = renderHook(() => useUnknownLockedSubmission<string, typeof outcome>(memory, KEY, () => id(++n)))
    const send = vi.fn()
      .mockRejectedValueOnce(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))
      .mockRejectedValueOnce(apiError(409, 'IDEMPOTENCY_KEY_REUSED'))
      .mockResolvedValueOnce(outcome)
    await act(() => result.current.run('first', send))
    expect(result.current.locked).toBe(true)
    // 잠긴 동안 run은 새 본문을 무시하고 보낸 본문을 같은 submissionId로 다시 보낸다.
    await act(() => result.current.run('changed', send))
    expect(result.current.status).toEqual({ kind: 'failed', code: 'IDEMPOTENCY_KEY_REUSED' })
    expect(result.current.pending).toBeNull()
    await act(() => result.current.run('next', send))
    expect(send.mock.calls).toEqual([[id(11), 'first'], [id(11), 'first'], [id(12), 'next']])
    expect(result.current.status).toEqual({ kind: 'decided', outcome })
  })
})

describe('useUnknownLockedSubmission', () => {
  it('does not send a second request while busy', async () => {
    const memory = createScreenMemory()
    let resolve: (o: typeof outcome) => void = () => undefined
    const send = vi.fn(() => new Promise<typeof outcome>((r) => { resolve = r }))
    let n = 20
    const { result } = renderHook(() => useUnknownLockedSubmission<string, typeof outcome>(memory, KEY, () => id(++n)))
    let first: Promise<void> = Promise.resolve()
    act(() => { first = result.current.run('a', send) })
    await act(() => result.current.run('b', send))
    expect(send).toHaveBeenCalledTimes(1)
    await act(async () => { resolve(outcome); await first })
    expect(result.current.status).toEqual({ kind: 'decided', outcome })
  })

  it.each([
    ['decided', () => Promise.resolve(outcome)],
    ['failed', () => Promise.reject(apiError(400, 'INVALID_INPUT'))],
  ] as const)('resetIfUnlocked clears a %s result', async (kind, send) => {
    const memory = createScreenMemory()
    let n = 30
    const { result } = renderHook(() => useUnknownLockedSubmission<string, typeof outcome>(memory, KEY, () => id(++n)))
    await act(() => result.current.run('a', send))
    expect(result.current.status.kind).toBe(kind)
    act(() => result.current.resetIfUnlocked())
    expect(result.current.status).toEqual({ kind: 'idle' })
    expect(memory.keys()).toEqual([])
  })

  it('resetIfUnlocked keeps an unknown lock (status and same pending)', async () => {
    const memory = createScreenMemory()
    let n = 40
    const { result, unmount } = renderHook(() => useUnknownLockedSubmission<string, typeof outcome>(memory, KEY, () => id(++n)))
    await act(() => result.current.run('a', () => Promise.reject(apiError(503, 'MASTER_OUTCOME_UNKNOWN'))))
    act(() => result.current.resetIfUnlocked())
    unmount()
    expect(statusOf(memory)).toEqual({ kind: 'unknown', code: 'MASTER_OUTCOME_UNKNOWN' })
    expect(pendingSubmissionOf(memory, KEY)).toEqual({ submissionId: id(41), payload: 'a' })
    expect(hasUnresolvedRequest(memory)).toBe(true)
  })
})

describe('sendUnknownLocked — releaseOnInvalidInput(MERCHANT-FLOW-001 FLOW-2c 리뷰 MINOR-4)', () => {
  it('기본(false): 결과 불명 뒤 재전송이 400 INVALID_INPUT이어도 잠금 유지(같은 submissionId·본문 보존)', async () => {
    const memory = createScreenMemory()
    const sent = { submissionId: 'a'.repeat(32) }
    const result = await sendUnknownLocked(memory, KEY, sent, true, () => Promise.reject(apiError(400, 'INVALID_INPUT')))
    expect(result.kind).toBe('unknown')
    expect(submissionStatusOf(memory, KEY)).toEqual({ kind: 'unknown', code: 'INVALID_INPUT' })
    expect(pendingSubmissionOf(memory, KEY)).toEqual(sent)
  })

  it('true: 재전송 400 INVALID_INPUT은 확정 실패 — 잠금 해제, 보낸 내용 지움(다음 제출은 새 submissionId); 첫 제출이 아니면(재전송 아님) 원래대로 확정 실패', async () => {
    const memory = createScreenMemory()
    const sent = { submissionId: 'b'.repeat(32) }
    const result = await sendUnknownLocked(memory, KEY, sent, true, () => Promise.reject(apiError(400, 'INVALID_INPUT')), undefined, true)
    expect(result.kind).toBe('failed')
    expect(submissionStatusOf(memory, KEY)).toEqual({ kind: 'failed', code: 'INVALID_INPUT' })
    expect(pendingSubmissionOf(memory, KEY)).toBeNull()
    // 같은 옵션이어도 다른 재전송 오류(503 MASTER_BUSY)는 결과를 모르므로 잠금 유지.
    const busy = await sendUnknownLocked(memory, KEY, sent, true, () => Promise.reject(apiError(503, 'MASTER_BUSY')), undefined, true)
    expect(busy.kind).toBe('unknown')
  })
})
