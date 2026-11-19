import { useId, type ReactNode } from 'react'
import { cn } from '../lib/utils'

/**
 * 폼 줄(ADMIN-UI-WORKSPACE-001 — 사용자 결정 2026-11-19, 사업자 등록 탭이 처음 적용): 이름은 왼쪽(140px 열 — 사업자 조회 상세 [DetailRow](DetailCard.tsx)와 같은 열·이름 글자·줄 사이 선),
 * 입력은 오른쪽 같은 줄 — 상세 화면과 같은 문서를 편집 모드로 보는 모양.
 * 도움말·오류는 입력 열 아래, 줄 끝 버튼(중복 확인·찾기·삭제 등 — [trailing])은 입력 바로 오른쪽. 720px 이하는 이름이 입력 위로(지금처럼) — 배치는 workspace.css
 * `.fieldrow`·`.fieldcontrol`·`.fieldnote`·`.fieldoffset`·`.fw-*`(PayCore 적응 블록, 색 없음).
 *
 * 접근성: 이름은 진짜 `<label for>`(입력 id — children 둘째 인자로 받아 입력에 붙인다)라 접근 가능한 이름은 [Field]와 같은 글자 그대로.
 * 도움말·오류는 [Field]와 같이 aria-describedby(children 첫째 인자)로 연결한다. 버튼은 label 밖이라 입력 이름에 섞이지 않는다.
 *
 * [width] = 입력 칸 폭 단계(DB 열 길이 기준 — xs ≤5자 · sm ≤14자 · md ≤50자 · lg ≤100자 · full >100자). `.fieldcontrol`의 첫 자식(입력 또는 선택 감싸개)에 최대 폭을 준다.
 * 다른 화면이 쓰는 법: [Field] 자리에 `<FormRow label hint error width trailing>{(describedBy, id) => <Input id={id} aria-describedby={describedBy} … />}</FormRow>`.
 */
export type FieldWidth = 'xs' | 'sm' | 'md' | 'lg' | 'full'

export function FormRow({ label, hint, error, width = 'full', trailing, className, children }: {
  label: string
  hint?: ReactNode
  error?: ReactNode
  width?: FieldWidth
  /** 입력 바로 오른쪽 같은 줄에 둘 버튼·진행 글자(선택). */
  trailing?: ReactNode
  className?: string
  children: (describedBy: string | undefined, id: string) => ReactNode
}) {
  const id = useId()
  const inputId = `${id}-input`
  const describedBy = error || hint ? `${id}-desc` : undefined
  // LAYOUT-ROLLOUT B2: 잠금(fieldset disabled) 때 줄 전체가 아니라 이름만 흐리게 — 입력·선택·.btn은 자기 disabled 모양(opacity)이 있고, 도움말·오류는 흐리지 않는다.
  // 조건은 입력 칸(input·select·textarea)이 disabled일 때만(reviewFix1 NIT-1 — trailing 버튼만 막힌 경우(예: 보내는 중 '찾기')에는 이름을 흐리지 않는다).
  return (
    <div className={cn('group/fieldrow fieldrow border-b py-2 last:border-b-0', className)}>
      <label htmlFor={inputId} className="text-xs text-muted-foreground group-has-[:is(input,select,textarea):disabled]/fieldrow:opacity-50">{label}</label>
      <div className={cn('fieldcontrol', width !== 'full' && `fw-${width}`)}>
        {children(describedBy, inputId)}
        {trailing}
      </div>
      {(error || hint) && (
        <p id={describedBy} className={cn('fieldnote text-xs', error ? 'text-destructive' : 'text-muted-foreground')}>
          {error ?? hint}
        </p>
      )}
    </div>
  )
}
