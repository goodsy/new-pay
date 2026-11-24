import type { MerchantDetail } from '../api/adminApi'
import { cn } from '../lib/utils'
import { activationChecklist, APPROVAL_GROUPS, pendingIn } from './MerchantActivationArea'

/** 단계 상태(표시만): 완료 / 진행 중 / 승인 대기(2인 승인 요청이 대기 중) / 미시작 / 준비 전(서버 기능 전). */
export type OpeningState = 'DONE' | 'IN_PROGRESS' | 'WAITING' | 'NOT_STARTED' | 'LATER'
export interface OpeningStep { key: string; label: string; state: OpeningState; note?: string }

/** MERCHANT-IA-001 B1(명세 rev 0.2 §3.2, Q-IA-5 답 "준비 전이라는 말로만"): LATER는 작업 배치 이름('B4 이후') 대신 "준비 전". */
const STATE_TEXT: Record<OpeningState, string> = { DONE: '완료', IN_PROGRESS: '진행 중', WAITING: '승인 대기', NOT_STARTED: '미시작', LATER: '준비 전' }

/**
 * 서비스 개통 현황(ADMIN-UI-MERCHANT-001 — 참고 시안 #merchant 타임라인, 사용자 지시 2026-11-11): **기존 상세 응답 값으로만** 계산한다(새 API·샘플 데이터 없음 — D-ADMIN-UI-003).
 * 진행 표시일 뿐 권위가 아니다 — 결제 가능 여부는 개통 점검표(C-43, 참고용)와 결제 시점 PAYMENT 판정을 따른다.
 * ① 사업자·계약 등록: 서비스 계약이 붙은 계약이 있으면 완료, 아니면 진행 중(상세가 있으면 사업자·가맹점은 이미 있다).
 * ② 계약 승인: 승인된 Revision이 있으면 완료, 계약 승인 요청이 대기 중이면 승인 대기.
 * ③ MID 발급: MID가 하나라도 있으면 완료, 첫 MID 발급 요청이 대기 중이면 승인 대기.
 * ④ 결제수단 개통·Site 심사: **한 MID에서** 개통 점검표 ②(그 MID의 [now]에 열린 대표 Site 심사 APPROVED)와 ③(그 MID의 카드 개통 ACTIVE)이 모두 충족이면 완료(Coordinator 결정 — 점검표와 같은 규칙, 표시 규칙), 개통·재개·Site 심사 승인 요청이 대기 중이면 승인 대기, 개통 등록이나 심사 결정이 하나라도 있으면 진행 중.
 * ⑤ 수수료·정산: 서버 기능 전 — 항상 "준비 전"(LATER).
 */
export function serviceOpeningTimeline(detail: MerchantDetail, now: Date): OpeningStep[] {
  const pick = (done: boolean, waiting: boolean, started: boolean): OpeningState => (done ? 'DONE' : waiting ? 'WAITING' : started ? 'IN_PROGRESS' : 'NOT_STARTED')
  const registered = detail.contracts.some((c) => c.agreements.length > 0)
  const approved = detail.contracts.some((c) => c.revisions.some((r) => r.approvedAt !== null))
  const pendingKind = (kind: string) => (detail.pendingApprovals ?? []).some((p) => p.kind === kind)
  const issued = detail.accounts.length > 0
  const methods = detail.accounts.flatMap((a) => a.paymentMethods)
  // 개통 점검표(activationChecklist)의 ② 대표 Site 심사·③ 카드 개통 판정을 그대로 쓴다 — 같은 MID에서 둘 다 충족해야 완료.
  const opened = detail.accounts.some((account) => {
    const met = new Set(activationChecklist(detail, account, now).filter((item) => item.state === 'MET').map((item) => item.key))
    return met.has('review') && met.has('method')
  })
  const reviewed = detail.sites.some((s) => s.review !== null)
  const openingPending = pendingIn(detail, APPROVAL_GROUPS.PM) || pendingIn(detail, APPROVAL_GROUPS.SITE_REVIEW)
  return [
    { key: 'registration', label: '사업자·계약 등록', state: registered ? 'DONE' : 'IN_PROGRESS' },
    { key: 'approval', label: '계약 승인', state: pick(approved, pendingKind('MERCHANT_CONTRACT_APPROVAL'), false) },
    { key: 'mid', label: 'MID 발급', state: pick(issued, pendingKind('MERCHANT_FIRST_MID_ISSUANCE'), false) },
    { key: 'opening', label: '결제수단 개통·Site 심사', state: pick(opened, openingPending, methods.length > 0 || reviewed) },
    // MERCHANT-IA-001 B1: '(예정)' 꼬리는 상태 글자 "준비 전"과 같은 말이라 뺐다.
    { key: 'terms', label: '수수료·정산', state: 'LATER' },
  ]
}

const DOT: Record<OpeningState, string> = {
  DONE: 'border-success bg-success',
  IN_PROGRESS: 'border-primary bg-primary/30',
  WAITING: 'border-warning bg-warning/40',
  NOT_STARTED: 'border-border bg-background',
  LATER: 'border-dashed border-border bg-background',
}

/** 상세 위쪽 가로 타임라인(작은 화면은 세로). 글자로 상태를 말한다 — 색만으로 구분하지 않는다. */
export function ServiceOpeningTimeline({ detail, now }: { detail: MerchantDetail; now: Date }) {
  const steps = serviceOpeningTimeline(detail, now)
  return (
    <section aria-label="서비스 개통 현황" className="flex flex-col gap-3 rounded-lg border p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h4 className="text-sm font-bold">서비스 개통 현황</h4>
        <p className="text-xs text-muted-foreground">등록·승인 기록으로 계산한 참고용 진행 표시입니다. 결제 가능 여부는 MID 발급 뒤 나오는 개통 점검표와 결제 시점 판정을 따릅니다.</p>
      </div>
      <ol className="grid gap-3 sm:grid-cols-5">
        {steps.map((step) => (
          <li key={step.key} className="flex items-start gap-2.5 sm:flex-col sm:gap-2">
            <span aria-hidden="true" className={cn('mt-0.5 size-3 shrink-0 rounded-full border-2', DOT[step.state])} />
            <span className="flex flex-col gap-0.5">
              <span className={cn('text-xs font-medium', step.state === 'NOT_STARTED' || step.state === 'LATER' ? 'text-muted-foreground' : 'text-foreground')}>
                {step.note ? `${step.label} ${step.note}` : step.label}
              </span>
              <span className="text-[11px] text-muted-foreground">{STATE_TEXT[step.state]}</span>
            </span>
          </li>
        ))}
      </ol>
    </section>
  )
}
