import { describe, expect, it, vi } from 'vitest'
import {
  BUSINESS_TARGET, labelBatches, loadMerchantTargetLabels, merchantTargetText, recordedCodeNote, sanitizeAuditDetail, TARGET_HIDDEN, type MerchantTargetLabel,
  type MerchantTargetLabels,
} from './merchantTarget'
import { MERCHANT_LABEL_MISSING } from './merchantLabel'

/**
 * MERCHANT-CODE-001 §8 MC-3-18 — `merchantTarget` 표시 규칙(§5.6.2): 라벨 있음 → "<상호> · <코드>", OK·없음 → "가맹점 정보 없음", UNAVAILABLE·로딩 → "—",
 * `biz_` → "사업자", 그 밖 원문, 분류 밖 `mer_`·`biz_` 포함 값 → "—". 어떤 분기 결과에도 `mer_`·`biz_`가 없다(라벨 상태 × 대상 값 전수 조합 — 속성 시험).
 * 명시 Fixture: 가맹점 `MER`(데모상점 · demoshop01), 사업자 `BIZ`, 계정 `adm_…`, Payment `pay_…`, 매입 파일 `ACQ…`.
 */
const ulid = (n: number) => `01JC${String(n).padStart(22, '0')}`
const MER = `mer_${ulid(1)}`
const MER2 = `mer_${ulid(2)}`
const BIZ = `biz_${ulid(1)}`
const found = (name: string, code: string): MerchantTargetLabel => ({ state: 'found', label: { name, code } })
const labels = (entries: [string, MerchantTargetLabel][]): MerchantTargetLabels => new Map(entries)

describe('merchantTargetText (MC-3-18 — §5.6.2)', () => {
  it('table: found / missing / unavailable / loading for a merchant target; business; other targets; defensive', () => {
    const table: [Parameters<typeof merchantTargetText>[0], MerchantTargetLabels, string][] = [
      // 가맹점 대상(키 = targetMerchantId) — 기존 행(target_ref mer_)·새 행(target_ref 코드) 모두 같은 라벨
      [{ targetRef: MER, targetMerchantId: MER }, labels([[MER, found('데모상점', 'demoshop01')]]), '데모상점 · demoshop01'],
      [{ targetRef: 'demoshop01', targetMerchantId: MER }, labels([[MER, found('데모상점', 'demoshop01')]]), '데모상점 · demoshop01'],
      [{ targetRef: MER, targetMerchantId: MER }, labels([[MER, { state: 'missing' }]]), MERCHANT_LABEL_MISSING],
      [{ targetRef: MER, targetMerchantId: MER }, labels([[MER, { state: 'unavailable' }]]), TARGET_HIDDEN],
      [{ targetRef: MER, targetMerchantId: MER }, labels([]), TARGET_HIDDEN],
      // 사업자 대상 — 종류 단어만(C-33)
      [{ targetRef: BIZ, targetMerchantId: null }, labels([]), BUSINESS_TARGET],
      // 그 밖 대상 — 원문
      [{ targetActorId: 'adm_T', targetRef: null }, labels([]), 'adm_T'],
      [{ targetRef: `pay_${ulid(9)}` }, labels([]), `pay_${ulid(9)}`],
      [{ targetRef: 'ACQ20261101-001' }, labels([]), 'ACQ20261101-001'],
      [{}, labels([]), ''],
      // 분류 밖 mer_·biz_ 포함 값(방어 — 정상 경로에서 없음)
      [{ targetRef: MER, targetMerchantId: null }, labels([]), TARGET_HIDDEN],
      [{ targetActorId: `x${MER}` }, labels([]), TARGET_HIDDEN],
      [{ targetRef: `site:${BIZ}` }, labels([]), TARGET_HIDDEN],
    ]
    for (const [target, map, expected] of table) expect(merchantTargetText(target, map), JSON.stringify(target)).toBe(expected)
  })

  it('property: no branch ever yields mer_ or biz_ (every label state × every target value, including hostile labels)', () => {
    const states: (MerchantTargetLabel | undefined)[] = [undefined, { state: 'missing' }, { state: 'unavailable' }, found('데모상점', 'demoshop01'), found(MER, 'demoshop01'), found('상점', BIZ)]
    const values = [null, undefined, '', MER, MER2, BIZ, 'demoshop01', 'adm_T', `pay_${ulid(3)}`, `x${MER}y`, `${BIZ};${MER}`, 'mer_', 'biz_x', 'ACQ1']
    let checked = 0
    for (const state of states) {
      for (const merchantId of [null, undefined, MER, MER2]) {
        for (const ref of values) {
          for (const actor of [null, undefined, 'adm_A', MER]) {
            const map = labels(state && merchantId ? [[merchantId, state]] : [])
            const text = merchantTargetText({ targetActorId: actor, targetRef: ref, targetMerchantId: merchantId }, map)
            expect(text.includes('mer_') || text.includes('biz_'), `${String(actor)} ${String(ref)} ${String(merchantId)} ${JSON.stringify(state)} → ${text}`).toBe(false)
            const note = recordedCodeNote({ targetRef: ref, targetMerchantId: merchantId }, map) ?? ''
            expect(note.includes('mer_') || note.includes('biz_')).toBe(false)
            checked += 1
          }
        }
      }
    }
    expect(checked).toBe(states.length * 4 * values.length * 4)
  })
})

describe('recordedCodeNote (§5.6.2 감사 "당시 표기")', () => {
  it('shows "(당시 <코드>)" only for a code-shaped target_ref that differs from the current code; mer_ rows and same code have none', () => {
    const map = labels([[MER, found('데모상점', 'newcode01')]])
    expect(recordedCodeNote({ targetRef: 'oldcode01', targetMerchantId: MER }, map)).toBe('(당시 oldcode01)')
    expect(recordedCodeNote({ targetRef: 'newcode01', targetMerchantId: MER }, map)).toBeNull()
    expect(recordedCodeNote({ targetRef: MER, targetMerchantId: MER }, map)).toBeNull()
    expect(recordedCodeNote({ targetRef: 'oldcode01', targetMerchantId: MER }, labels([[MER, { state: 'unavailable' }]]))).toBeNull()
    expect(recordedCodeNote({ targetRef: 'Bad_Code', targetMerchantId: MER }, map)).toBeNull()
  })
})

describe('sanitizeAuditDetail (§5.6.2 — C-33 방어 치환)', () => {
  it('replaces (mer|biz)_<ULID> tokens with "—" and keeps the rest', () => {
    expect(sanitizeAuditDetail(`kind=X;merchant=${MER};biz=${BIZ};code=demoshop01`)).toBe('kind=X;merchant=—;biz=—;code=demoshop01')
    expect(sanitizeAuditDetail('from=oldcode01;to=newcode01;reason=TYPO_CORRECTION')).toBe('from=oldcode01;to=newcode01;reason=TYPO_CORRECTION')
  })

  it('reviewFix1 NIT-1: a lowercase or short token the ULID pattern misses still turns the whole summary into "—" (last defence)', () => {
    expect(sanitizeAuditDetail(`merchant=${MER.toLowerCase()};code=demoshop01`)).toBe(TARGET_HIDDEN)
    expect(sanitizeAuditDetail('merchant=mer_ABC;code=demoshop01')).toBe(TARGET_HIDDEN)
    expect(sanitizeAuditDetail('business=biz_1')).toBe(TARGET_HIDDEN)
    expect(sanitizeAuditDetail(`merchant=${MER};other=mer_x`)).toBe(TARGET_HIDDEN)
  })
})

describe('labelBatches / loadMerchantTargetLabels (§5.6.3 화면 묶음)', () => {
  it('unique sorted keys in batches of 50; nothing for 0 keys', () => {
    expect(labelBatches([null, undefined])).toEqual([])
    expect(labelBatches([MER2, MER, MER2, null])).toEqual([[MER, MER2]])
    const many = Array.from({ length: 120 }, (_, i) => `mer_${ulid(1000 + i)}`)
    const batches = labelBatches([...many].reverse())
    expect(batches.map((b) => b.length)).toEqual([50, 50, 20])
    expect(batches.flat()).toEqual([...many].sort())
  })

  it('OK → found/missing per key; UNAVAILABLE, 429, a network error or a response outside the sent set → that batch unavailable', async () => {
    const ok = vi.fn(async () => ({ items: [{ merchantId: MER, name: '데모상점', code: 'demoshop01' }], labels: 'OK' as const }))
    expect([...(await loadMerchantTargetLabels({ merchantLabels: ok }, [MER, MER2])).entries()]).toEqual([[MER, found('데모상점', 'demoshop01')], [MER2, { state: 'missing' }]])
    expect(ok).toHaveBeenCalledWith([MER, MER2])
    for (const merchantLabels of [
      vi.fn(async () => ({ items: [], labels: 'UNAVAILABLE' as const })),
      vi.fn(async () => { throw Object.assign(new Error('429'), { status: 429, code: 'RATE_LIMITED' }) }),
      vi.fn(async () => { throw new TypeError('network') }),
      vi.fn(async () => ({ items: [{ merchantId: `mer_${ulid(77)}`, name: '다른 상점', code: 'othershop1' }], labels: 'OK' as const })),
      vi.fn(async () => ({ items: [{ merchantId: MER, name: '데모상점', code: 'demoshop01' }, { merchantId: MER, name: '데모상점', code: 'demoshop01' }], labels: 'OK' as const })),
    ]) {
      const result = await loadMerchantTargetLabels({ merchantLabels }, [MER, MER2])
      expect([...result.values()]).toEqual([{ state: 'unavailable' }, { state: 'unavailable' }])
    }
  })
})
