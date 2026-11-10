import { useId, type ReactNode } from 'react'
import type { AdminApi } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { defaultSubmissionId, kstDate } from '../lib/registration'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { RegistrationStep, type StepStatus } from './RegistrationStep'
import { Field } from './DataBits'
import { Input } from './ui/input'
import { Label } from './ui/label'

/**
 * 계약 Revision 등록 폼(MERCHANT-OPS-002 §9.2·§9.6 B2-1 — OPERATOR 1인, 미승인 Revision으로 남는다 — 권리 변경 없음). 가맹점 조회 상세의 계약 영역에서 연다.
 * - 대상 가맹점은 상세의 가맹점으로 고정한다(계약 대상 Merchant는 바꿀 수 없다 — D-MD-013). 범위는 가맹점 전체 또는 이 가맹점의 Site 선택.
 * - 날짜는 Asia/Seoul 날짜(C-7 — 서버가 그 날 0시 KST로 바꾼다), 종료일은 "이 날 0시 종료(미포함)". 시작일 최솟값(오늘 KST)은 안내일 뿐 — 소급 판정은 MASTER(D-MOPS-007 ②).
 * - 제출·결과 불명 잠금·같은 submissionId 재전송은 가맹점 등록 화면 단계와 같은 [RegistrationStep]을 쓴다(§5.3).
 * - 성공하면 완료 상태(C-32): 입력 읽기 전용·제출 버튼 없음 — 같은 폼을 다시 눌러 새 submissionId로 Revision n+1이 중복 생성되지 않게. "다른 Revision 새로 입력"만 빈 폼을 준다.
 * - 상태(잠금·submissionId·보낸 입력·폼 입력)는 호출자가 준 [memory]에 **계약별 키**로 둔다(리뷰 C-M1): 폼을 닫거나 다른 가맹점을 열어도 잠금이 남아, 다시 열면 같은 submissionId로만 보낼 수 있다.
 *   새로 입력하려면 §5.3 경고를 거친 "취소하고 새로 입력"뿐이다. 메모리는 App이 세션 경계마다 새로 만든다(B1과 같음 — 브라우저 저장소 없음).
 */
interface Props {
  api: AdminApi
  /** 계약별 폼 상태를 둘 메모리(App 수준 — 세션 경계에서만 새로). */
  memory: ScreenMemory
  contractId: string
  merchantId: string
  /** 범위 선택 후보(이 가맹점의 Site). */
  siteIds: readonly string[]
  /** 성공 뒤(상세 다시 읽기 등). */
  onRegistered: (reference: string) => void
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
  todayKst?: () => string
}

type Payload = { contractId: string; scopeMerchantId: string; scopeSiteIds: string[]; effectiveFromDate: string; effectiveToDate?: string }

const DATE = /^[0-9]{4}-[0-9]{2}-[0-9]{2}$/
const NO_SITES: readonly string[] = Object.freeze([])
const NO_ERRORS: Readonly<{ start?: boolean; end?: boolean; sites?: boolean }> = Object.freeze({})
const IDLE_STATUS: StepStatus = { kind: 'idle' }

/** 계약 하나의 폼 메모리 키 접두사. */
const keyOf = (contractId: string) => `revision.${contractId}`

/** 이 계약의 Revision 폼이 결과 불명으로 잠겨 있는지(보내는 중 포함) — 폼이 닫혀 있어도 버튼이 알리도록 구독한다. */
export function useRevisionLocked(memory: ScreenMemory, contractId: string): boolean {
  const [status] = useRemembered<StepStatus>(memory, `${keyOf(contractId)}.step.status`, IDLE_STATUS)
  return status.kind === 'busy' || status.kind === 'unknown'
}
const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>

export function ContractRevisionForm({ api, memory, contractId, merchantId, siteIds, onRegistered, onSessionEnded, newSubmissionId = defaultSubmissionId, todayKst = () => kstDate(new Date()) }: Props) {
  const key = keyOf(contractId)
  const [scopeKind, setScopeKind] = useRemembered<'MERCHANT' | 'SITES'>(memory, `${key}.scopeKind`, 'MERCHANT')
  const [checked, setChecked] = useRemembered<readonly string[]>(memory, `${key}.checked`, NO_SITES)
  const [startDate, setStartDate] = useRemembered(memory, `${key}.startDate`, '')
  const [endDate, setEndDate] = useRemembered(memory, `${key}.endDate`, '')
  const [errors, setErrors] = useRemembered(memory, `${key}.errors`, NO_ERRORS)
  const endNoteId = useId()
  const radioName = useId()

  return (
    <RegistrationStep<Payload>
      memory={memory}
      memoryKey={`${key}.step`}
      title={`새 Revision 등록 — ${contractId}`}
      submitLabel="Revision 등록"
      api={{ send: (input) => api.registerContractRevision(input) }}
      newSubmissionId={newSubmissionId}
      onSessionEnded={onSessionEnded}
      collect={() => {
        const sites = scopeKind === 'SITES' ? checked.filter((id) => siteIds.includes(id)) : []
        const next = {
          start: !DATE.test(startDate),
          end: endDate !== '' && (!DATE.test(endDate) || (DATE.test(startDate) && endDate <= startDate)),
          sites: scopeKind === 'SITES' && sites.length === 0,
        }
        setErrors(next)
        if (Object.values(next).some(Boolean)) return null
        // 시작일이 오늘(KST)보다 앞이어도 막지 않는다 — 판정은 MASTER(D-MOPS-007 ②, 거절 문구로 안내).
        return { contractId, scopeMerchantId: merchantId, scopeSiteIds: sites, effectiveFromDate: startDate, ...(endDate ? { effectiveToDate: endDate } : {}) }
      }}
      onSucceeded={(reference) => onRegistered(reference)}
      succeeded={(reference) => <span>Revision <Mono>{reference}</Mono> ({REVISION_AWAITING_APPROVAL} — 승인은 2인 승인 기능에서)</span>}
      completion={{
        resetLabel: '다른 Revision 새로 입력',
        onReset: () => {
          setScopeKind('MERCHANT')
          setChecked(NO_SITES)
          setStartDate('')
          setEndDate('')
          setErrors(NO_ERRORS)
        },
      }}
    >
      {() => (
        <>
          <fieldset className="flex flex-col gap-2 text-sm">
            <legend className="mb-1 text-sm font-medium">범위</legend>
            <label className="flex items-center gap-2">
              <input type="radio" name={radioName} value="MERCHANT" checked={scopeKind === 'MERCHANT'} onChange={() => setScopeKind('MERCHANT')} />
              가맹점 전체
            </label>
            <label className="flex items-center gap-2">
              <input type="radio" name={radioName} value="SITES" checked={scopeKind === 'SITES'} onChange={() => setScopeKind('SITES')} />
              선택 Site
            </label>
            {scopeKind === 'SITES' && siteIds.map((id) => (
              <label key={id} className="flex items-center gap-2 pl-6">
                <input type="checkbox" checked={checked.includes(id)} onChange={(e) => setChecked(e.target.checked ? [...checked, id] : checked.filter((c) => c !== id))} />
                <Mono>{id}</Mono>
              </label>
            ))}
            {errors.sites && <p className="text-xs text-destructive">Site를 하나 이상 고르세요.</p>}
          </fieldset>
          <Field label="시작일" hint="오늘(한국 시간) 이후 날짜. 그 날 0시부터 효력 — 오늘보다 앞이면 서버가 거절합니다." error={errors.start ? '시작일을 고르세요.' : undefined}>
            {(describedBy) => (
              <Input type="date" min={todayKst()} value={startDate} onChange={(e) => setStartDate(e.target.value)} aria-describedby={describedBy} aria-invalid={errors.start || undefined} />
            )}
          </Field>
          <div className="flex flex-col gap-1.5">
            <div className="flex items-end gap-2">
              <Label className="flex-1">
                <span>종료일</span>
                <Input type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} aria-describedby={endNoteId} aria-invalid={errors.end || undefined} />
              </Label>
              <span id={endNoteId} className="pb-2 text-xs text-muted-foreground whitespace-nowrap">이 날 0시 종료(미포함)</span>
            </div>
            {errors.end
              ? <p className="text-xs text-destructive">종료일은 시작일보다 뒤여야 합니다.</p>
              : <p className="text-xs text-muted-foreground">선택 — 비우면 종료 없음.</p>}
          </div>
        </>
      )}
    </RegistrationStep>
  )
}
