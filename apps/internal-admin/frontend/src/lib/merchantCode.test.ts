import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'
import { codePrefix, composeMerchantCode, MERCHANT_CODE, PREFIX_MAX, RANDOM_ALPHABET, randomTail, suggestMerchantCode, TAIL_LENGTH, type RandomBytes } from './merchantCode'

/** 시험용 결정적 난수(LCG) — 속성 시험의 입력 생성과 주입 난수 소스. */
function lcg(seed: number) {
  let state = seed >>> 0
  return () => {
    state = (Math.imul(state, 1664525) + 1013904223) >>> 0
    return state
  }
}
const seededBytes = (seed: number): RandomBytes => {
  const next = lcg(seed)
  return (length) => Uint8Array.from({ length }, () => next() & 0xff)
}
/** 아무 상호(영문 대소문자·숫자·공백·문장부호·한글·이모지 섞음, 0~40자). */
const CHARS = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -_.&\'()가나다라마페이코어☕é'
function arbitraryName(next: () => number): string {
  const length = next() % 41
  let s = ''
  for (let i = 0; i < length; i++) s += Array.from(CHARS)[next() % Array.from(CHARS).length]
  return s
}

describe('MERCHANT-CODE-001 MC-2-1 code preview (§3.4)', () => {
  it('C-3: the random alphabet is 31 characters without 0 o 1 l i', () => {
    expect(RANDOM_ALPHABET).toHaveLength(31)
    expect(new Set(RANDOM_ALPHABET).size).toBe(31)
    for (const confusing of ['0', 'o', '1', 'l', 'i']) expect(RANDOM_ALPHABET).not.toContain(confusing)
  })

  it('§3.4 prefix: letters/digits only, lower-cased, cut before the first letter, at most 10 (no transliteration — Korean is dropped)', () => {
    expect(codePrefix('Paycore Coffee & Bakery')).toBe('paycorecof')
    expect(codePrefix('7-Eleven Seoul')).toBe('elevenseou')
    expect(codePrefix('2024 Shop')).toBe('shop')
    expect(codePrefix('페이코어 커피')).toBe('')
    expect(codePrefix('A1')).toBe('a1')
    expect(codePrefix('')).toBe('')
  })

  it('§3.4 compose: prefix + 4 random; under 6 characters is padded with random; only an empty prefix uses the letter-only first tail character', () => {
    // 재리뷰 NIT-1: 꼬리 첫 글자(영문 23자 중)는 앞부분이 빈 경우에만 — 앞부분이 있으면 둘째 글자부터(31자 중).
    const tail = 'xk3m9p'
    expect(composeMerchantCode('Paycore Coffee', tail)).toBe('paycorecof' + 'k3m9')
    expect(composeMerchantCode('A1', tail)).toBe('a1' + 'k3m9')
    expect(composeMerchantCode('Q', tail)).toBe('q' + 'k3m9p')
    expect(composeMerchantCode('커피', tail)).toBe('xk3m9p')
  })

  it('MINOR-1 rejection sampling boundaries: bytes 253..255 are discarded for the 23-letter slot, 248..255 for the 31-character slots (no modulo bias)', () => {
    const LETTERS = 'abcdefghjkmnpqrstuvwxyz'
    // 23자 자리: 256 - 256 % 23 = 253 → 253·254·255 버림, 다음 바이트 k=5 → LETTERS[5] = 'f'(변이 byte % 23이면 253 % 23 = 0 → 'a').
    // 31자 자리: 256 - 256 % 31 = 248 → 248..255 버림, 다음 바이트 k=10 → RANDOM_ALPHABET[10] = 'm'(변이 byte % 31이면 248 % 31 = 0 → 'a').
    const queue = [253, 254, 255, 5, 248, 249, 250, 251, 252, 253, 254, 255, 10, 1, 2, 3, 4]
    const bytes: RandomBytes = (length) => Uint8Array.from({ length }, () => queue.shift()!)
    const tail = randomTail(bytes)
    expect(tail[0]).toBe(LETTERS[5])
    expect(tail[1]).toBe(RANDOM_ALPHABET[10])
    expect(tail).toBe('fmbcde')
    expect(queue).toEqual([])
    // 경계 바로 아래 값은 받는다: 252(23자 자리) → 252 % 23 = 22 → 'z', 247(31자 자리) → 247 % 31 = 30 → '9'.
    const edge = [252, 247, 0, 0, 0, 0]
    expect(randomTail((length) => Uint8Array.from({ length }, () => edge.shift()!))).toBe('z9aaaa')
  })

  it('property: for 2,000 arbitrary names and random sources the preview always matches §3.1, is 6..14 long, keeps the name prefix and has no confusing character in the random part', () => {
    const names = lcg(20261114)
    for (let run = 0; run < 2000; run++) {
      const name = arbitraryName(names)
      const code = suggestMerchantCode(name, seededBytes(run))
      expect(code, name).toMatch(MERCHANT_CODE)
      expect(code.length).toBeGreaterThanOrEqual(6)
      expect(code.length).toBeLessThanOrEqual(14)
      const prefix = codePrefix(name)
      expect(prefix.length).toBeLessThanOrEqual(PREFIX_MAX)
      expect(code.startsWith(prefix)).toBe(true)
      const random = code.slice(prefix.length)
      expect(random.length).toBe(Math.max(TAIL_LENGTH, 6 - prefix.length))
      for (const c of random) expect(RANDOM_ALPHABET, `${name} -> ${code}`).toContain(c)
    }
  })

  it('property: the default source (Web Crypto) gives a §3.1 tail whose first character is a letter, and two suggestions differ', () => {
    const seen = new Set<string>()
    for (let run = 0; run < 200; run++) {
      const tail = randomTail()
      expect(tail).toMatch(/^[a-z][a-z0-9]{5}$/)
      for (const c of tail) expect(RANDOM_ALPHABET).toContain(c)
      seen.add(tail)
    }
    expect(seen.size).toBeGreaterThan(190)
  })

  it('MC-2-4 source check: the preview uses crypto.getRandomValues and never Math.random', () => {
    const source = readFileSync('src/lib/merchantCode.ts', 'utf8') // Vitest 작업 디렉터리 = frontend/
    expect(source).toContain('crypto.getRandomValues(')
    expect(source).not.toMatch(/Math\.random/)
  })
})
