import type { MerchantDetail } from '../api/adminApi'
import { activationChecklist, CHECK_TEXT } from './MerchantActivationArea'
import { serviceOpeningTimeline, STATE_TEXT, type OpeningState } from './ServiceOpeningTimeline'

type Account = MerchantDetail['accounts'][number]
type Mark = 'done' | 'partial' | 'none'

/** 한 항목. [mark]는 모양(●/◐/○ — 글자와 함께, 색만으로 말하지 않는다). */
export interface ReadinessItem { key: string; label: string; state: string; mark: Mark }

const MARK_OF: Record<OpeningState, Mark> = { DONE: 'done', IN_PROGRESS: 'partial', WAITING: 'partial', NOT_STARTED: 'none', LATER: 'none' }
const SYMBOL: Record<Mark, string> = { done: '●', partial: '◐', none: '○' }

/**
 * 결제 준비 상태 줄의 값(MERCHANT-IA-001 B4 — D-ADMIN-UI-011 ①, pg-lab 상점 상세 머리 '결제 준비' 줄). **새 판정을 만들지 않는다**:
 * 계약 승인·MID 발급·결제수단 개통·Site 심사·수수료·정산은 서비스 개통 현황([serviceOpeningTimeline]) 값 그대로, 결제 생성 조건·카드 Route는 고른 MID의 개통 점검표([activationChecklist]) 값 그대로.
 * 참고용이다 — 결제 가능 여부의 권위는 결제 시점 PAYMENT 판정.
 */
export function paymentReadiness(detail: MerchantDetail, account: Account | null, now: Date): ReadinessItem[] {
  const steps = serviceOpeningTimeline(detail, now)
  const step = (key: string): ReadinessItem => {
    const s = steps.find((x) => x.key === key)!
    return { key, label: s.label, state: STATE_TEXT[s.state], mark: MARK_OF[s.state] }
  }
  const items = [step('approval'), step('mid'), step('opening')]
  if (account !== null) {
    const checklist = activationChecklist(detail, account, now)
    const creation = checklist.filter((c) => c.group === '결제 생성 조건').every((c) => c.state === 'MET')
    const route = checklist.find((c) => c.key === 'route')!
    items.push({ key: 'creation', label: '결제 생성 조건', state: creation ? '충족' : '미충족', mark: creation ? 'done' : 'none' })
    items.push({ key: 'route', label: '카드 Route', state: CHECK_TEXT[route.state], mark: route.state === 'MET' ? 'done' : 'none' })
  }
  items.push(step('terms'))
  return items
}

/** 상세 머리 한 줄(탭 위). 글자로 상태를 말하고 모양 기호는 보조(aria-hidden). */
export function PaymentReadinessLine({ detail, account, now }: { detail: MerchantDetail; account: Account | null; now: Date }) {
  const items = paymentReadiness(detail, account, now)
  return (
    <section aria-label="결제 준비(참고)" className="flex flex-wrap items-center gap-x-4 gap-y-1 rounded-lg border px-4 py-2 text-xs">
      <span className="font-semibold">결제 준비(참고)</span>
      <ul className="flex flex-wrap items-center gap-x-4 gap-y-1">
        {items.map((item) => (
          <li key={item.key} aria-label={`${item.label}: ${item.state}`} data-mark={item.mark} className="flex items-center gap-1">
            <span aria-hidden="true">{SYMBOL[item.mark]}</span>
            <span className="text-muted-foreground">{item.label}</span>
            <span className={item.mark === 'done' ? 'font-semibold' : undefined}>{item.state}</span>
          </li>
        ))}
      </ul>
      <span className="text-muted-foreground">{account !== null ? '결제 생성 조건·카드 Route는 선택 MID 기준, 결제수단 개통·Site 심사는 어느 MID든 기준' : '결제수단 개통·Site 심사는 어느 MID든 기준'}</span>
    </section>
  )
}
