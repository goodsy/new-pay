import { useId, type ReactNode } from 'react'
import type { AdminApi } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { defaultSubmissionId, kstDate } from '../lib/registration'
import { REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { RegistrationStep, type StepStatus } from './RegistrationStep'
import { FormRow } from './FormRow'
import { Input } from './ui/input'
import { revisionReferenceLabel } from '../lib/merchantLabels'

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
  /** 화면에 보이는 계약 이름(MERCHANT-IA-001 B3 — "계약 n"; 계약 키 ctr_는 메모리 키·호출 인자로만). 없으면 "계약". */
  contractLabel?: string
  merchantId: string
  /** 범위 선택 후보(이 가맹점의 Site — 값은 내부 키, 호출 인자로만). */
  siteIds: readonly string[]
  /** 후보 Site의 화면 이름(B3 — 채널 식별값). 없으면 "Site n". */
  siteLabel?: (siteId: string) => string
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

export function ContractRevisionForm({ api, memory, contractId, contractLabel = '계약', merchantId, siteIds, siteLabel, onRegistered, onSessionEnded, newSubmissionId = defaultSubmissionId, todayKst = () => kstDate(new Date()) }: Props) {
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
      rows
      // MERCHANT-IA-001 B3(D-ADMIN-UI-010 ③): 제목·결과·Site 후보에 내부 키(ctr_·site_)를 그리지 않는다.
      title={`새 Revision 등록 — ${contractLabel}`}
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
      succeeded={(reference) => <span><Mono>{revisionReferenceLabel(reference)}</Mono> ({REVISION_AWAITING_APPROVAL} — 승인은 2인 승인 기능에서)</span>}
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
          {/*
            LAYOUT-ROLLOUT B5(사용자 결정 2026-11-19 폼 줄): 범위는 가맹점 등록 ③ 범위 줄(B2)과 같은 줄 모양(.fieldrow — 이름 span aria-hidden 왼쪽 · 선택지 오른쪽).
            접근 이름은 legend 그대로(화면에서는 sr-only — 보이는 legend는 fieldset 테두리 자리에 그려져 grid 칸이 되지 않는다). Site 체크·오류는 선택지 열 아래.
          */}
          <fieldset className="m-0 min-w-0 border-0 p-0 text-sm">
            <legend className="sr-only">범위</legend>
            <div className="group/fieldrow fieldrow border-b py-2">
              <span aria-hidden="true" className="text-xs text-muted-foreground group-has-[:is(input,select,textarea):disabled]/fieldrow:opacity-50">범위</span>
              <span className="flex flex-col gap-2">
                <span className="flex flex-wrap gap-4">
                  <label className="flex items-center gap-2">
                    <input type="radio" name={radioName} value="MERCHANT" checked={scopeKind === 'MERCHANT'} onChange={() => setScopeKind('MERCHANT')} />
                    가맹점 전체
                  </label>
                  <label className="flex items-center gap-2">
                    <input type="radio" name={radioName} value="SITES" checked={scopeKind === 'SITES'} onChange={() => setScopeKind('SITES')} />
                    선택 Site
                  </label>
                </span>
                {scopeKind === 'SITES' && siteIds.map((id, index) => (
                  <label key={id} className="flex items-center gap-2 pl-6">
                    <input type="checkbox" checked={checked.includes(id)} onChange={(e) => setChecked(e.target.checked ? [...checked, id] : checked.filter((c) => c !== id))} />
                    <Mono>{siteLabel ? siteLabel(id) : `Site ${index + 1}`}</Mono>
                  </label>
                ))}
              </span>
              {errors.sites && <p className="fieldnote text-xs text-destructive">Site를 하나 이상 고르세요.</p>}
            </div>
          </fieldset>
          {/* B5: 시작일·종료일 sm(DATE — 가맹점 등록 ③과 같은 폭). 문구·설명 연결·aria-invalid 그대로. */}
          <FormRow label="시작일" width="sm" hint="오늘(한국 시간) 이후 날짜. 그 날 0시부터 효력 — 오늘보다 앞이면 서버가 거절합니다." error={errors.start ? '시작일을 고르세요.' : undefined}>
            {(describedBy, id) => (
              <Input id={id} type="date" min={todayKst()} value={startDate} onChange={(e) => setStartDate(e.target.value)} aria-describedby={describedBy} aria-invalid={errors.start || undefined} />
            )}
          </FormRow>
          {/*
            C-7 배타 끝: 고정 문구를 입력 바로 옆에(폼 줄 trailing). 설명 연결은 기존처럼 그 문구 하나(aria-describedby = endNoteId),
            선택 안내/오류 문구는 입력 열 아래 .fieldnote(기존처럼 설명에는 연결하지 않음 — 모양만 바꾼다).
          */}
          <FormRow
            label="종료일"
            width="sm"
            error={errors.end ? '종료일은 시작일보다 뒤여야 합니다.' : undefined}
            hint="선택 — 비우면 종료 없음."
            trailing={<span id={endNoteId} className="text-xs text-muted-foreground whitespace-nowrap">이 날 0시 종료(미포함)</span>}
          >
            {(_describedBy, id) => (
              <Input id={id} type="date" value={endDate} onChange={(e) => setEndDate(e.target.value)} aria-describedby={endNoteId} aria-invalid={errors.end || undefined} />
            )}
          </FormRow>
        </>
      )}
    </RegistrationStep>
  )
}
