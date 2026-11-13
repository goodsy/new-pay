import { useState } from 'react'
import type { AdminApi } from '../api/adminApi'
import type { ConsoleErrorCode } from '../errors/errorCodes'
import { Field } from '../components/DataBits'
import { PageHeader } from '../components/PageHeader'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { RegistrationStep, type StepSpec } from '../components/RegistrationStep'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { WarningNote } from '../components/Notice'
import { defaultSubmissionId, maskRegistrationNumber, type BusinessRef, type HandoffState } from '../lib/registration'
import { isRegistrationNumber } from '../lib/registrationNumber'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'

/**
 * 사업자 등록 화면(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ④ "화면만 분리, 권한은 그대로"): 기존 마법사 ① 사업자 카드를 떼어 낸 화면. OPERATOR만(표시만 — 판정·감사는 서버).
 * 저장 호출·body·submissionId·결과 불명 잠금·거절 문구는 MERCHANT-OPS-002 §5 B1 그대로([RegistrationStep]).
 * - 사업자등록번호 입력은 `000-00-00000`으로 보이지만 보내는 값은 숫자 10자리([RegistrationNumberInput]). 제출하면 입력란을 비우고 화면에는 마스킹 값만.
 *   평문 입력값은 화면 자체 상태라 화면을 바꾸면 비워진다(재전송할 payload만 [memory]에 — 리뷰 MINOR-2와 같다).
 * - 사업자 ID(biz_…)는 화면에 그리지 않는다(D-ADMIN-UI-004 ③) — 성공하면 상호·마스킹 번호만 보이고, "이 사업자로 가맹점 등록"이 내부 키를 가맹점 등록 화면에 넘긴다.
 * - 중복 등록번호는 고정 문구만(D-MOPS-006 ③ — 기존 사업자 ID·링크 없음).
 */
interface Props {
  api: AdminApi
  /** "이 사업자로 가맹점 등록" — 가맹점 등록 화면을 이 사업자로 새로 시작해 연다(내부 키 전달 — 화면 표시 없음). [handoffState]가 locked가 아닐 때만 부른다. */
  onContinue: (business: BusinessRef) => void
  /** 가맹점 등록 마법사 상태(리뷰 MINOR-1): locked면 넘기지 않고 경고, inProgress면 "새로 시작할지" 확인 뒤에만 [onContinue]. 없으면 empty. */
  handoffState?: () => HandoffState
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
  /** 화면 상태를 둘 App 메모리(화면 전환에도 유지 — 브라우저 저장소 아님). 세션 경계마다 App이 새로 만든다. */
  memory?: ScreenMemory
}

const NO_ERRORS: Readonly<{ number?: boolean; name?: boolean }> = Object.freeze({})

export const validName = (value: string) => value.length > 0 && [...value].length <= 100 && value === value.trim() && !/\p{Cc}/u.test(value)

export function BusinessRegistrationScreen({ api, onContinue, handoffState = () => 'empty', onSessionEnded, newSubmissionId = defaultSubmissionId, memory: injected }: Props) {
  /** 넘겨주기 안내(locked 경고 / inProgress 확인) — 화면 자체 상태. */
  const [handoff, setHandoff] = useState<'locked' | 'confirm' | null>(null)
  const [own] = useState(createScreenMemory)
  const memory = injected ?? own
  const read = <T,>(key: string, initial: T): T => (memory.has(key) ? (memory.read(key) as T) : initial)
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [businessName, setBusinessName] = useRemembered(memory, 'businessName', '')
  const [errors, setErrors] = useRemembered(memory, 'businessErrors', NO_ERRORS)
  const [business, setBusiness] = useRemembered<BusinessRef | null>(memory, 'business', null)

  const spec: StepSpec<{ registrationNumber: string; displayName: string }> = {
    memory,
    newSubmissionId,
    onSessionEnded,
    memoryKey: 'step.business',
    api: { send: (input) => api.registerBusiness(input) },
    collect: () => {
      const name = read<string>('businessName', '')
      const next = { number: !isRegistrationNumber(registrationNumber), name: !validName(name) }
      setErrors(next)
      return next.number || next.name ? null : { registrationNumber, displayName: name }
    },
    afterSubmit: () => setRegistrationNumber(''),
    onSucceeded: (reference, payload) => setBusiness({ businessId: reference, name: payload.displayName, masked: maskRegistrationNumber(payload.registrationNumber) }),
  }

  return (
    <section className="flex flex-col gap-6">
      <PageHeader title="사업자 등록" description="사업자를 등록합니다. 가맹점은 '가맹점 등록'에서 이어서 등록하세요." />
      <RegistrationStep
        {...spec}
        title="사업자"
        submitLabel="사업자 등록"
        succeeded={(reference) => business && business.businessId === reference && (
          <span className="flex flex-col gap-0.5">
            <span className="font-medium">{business.name}</span>
            <span>사업자등록번호 <span className="font-mono">{business.masked}</span></span>
          </span>
        )}
        completion={{ resetLabel: '사업자 새로 입력', onReset: () => { setBusinessName(''); setErrors(NO_ERRORS); setBusiness(null); setHandoff(null) } }}
      >
        {() => (
          <>
            <Field label="사업자등록번호" error={errors.number ? '사업자등록번호는 숫자 10자리입니다.' : undefined}>
              {(describedBy) => (
                <RegistrationNumberInput value={registrationNumber} onValueChange={setRegistrationNumber} aria-describedby={describedBy} aria-invalid={errors.number || undefined} />
              )}
            </Field>
            <Field label="사업자명" error={errors.name ? '1~100자, 앞뒤 공백 없이 입력하세요.' : undefined}>
              {(describedBy) => (
                <Input value={businessName} onChange={(e) => setBusinessName(e.target.value)} autoComplete="off" aria-describedby={describedBy} aria-invalid={errors.name || undefined} />
              )}
            </Field>
          </>
        )}
      </RegistrationStep>
      {business && (
        <div className="flex flex-col gap-3 rounded-lg border border-dashed px-4 py-3 text-sm">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span>{`${business.name}(${business.masked})에 가맹점을 등록하려면`}</span>
            <Button
              type="button"
              onClick={() => {
                const state = handoffState()
                if (state === 'locked') setHandoff('locked')
                else if (state === 'inProgress') setHandoff('confirm')
                else {
                  setHandoff(null)
                  onContinue(business)
                }
              }}
            >
              이 사업자로 가맹점 등록
            </Button>
          </div>
          {handoff === 'locked' && (
            <WarningNote>가맹점 등록에 결과 확인이 필요한 단계가 있습니다 — 가맹점 등록에서 '같은 내용 다시 보내기'로 먼저 확인하세요.</WarningNote>
          )}
          {handoff === 'confirm' && (
            <div role="group" aria-label="가맹점 등록 새로 시작 확인" className="flex flex-col gap-2">
              <WarningNote>진행 중인 가맹점 등록이 있습니다. 그 내용을 지우고 이 사업자로 새로 시작할까요? (이미 저장된 가맹점·계약은 지워지지 않습니다)</WarningNote>
              <div className="flex justify-end gap-2">
                <Button type="button" variant="outline" onClick={() => setHandoff(null)}>취소</Button>
                <Button
                  type="button"
                  onClick={() => {
                    setHandoff(null)
                    // 확인 사이에 잠겼을 수 있다 — 다시 본다.
                    if (handoffState() === 'locked') setHandoff('locked')
                    else onContinue(business)
                  }}
                >
                  새로 시작
                </Button>
              </div>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
