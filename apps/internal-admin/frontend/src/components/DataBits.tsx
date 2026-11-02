import { useId, type ReactNode } from 'react'
import { InboxIcon, LoaderCircleIcon } from './icons'
import { formatKst } from '../lib/format'
import { labelOf } from '../lib/labels'
import { statusTone } from '../lib/statusTone'
import { Badge } from './ui/badge'
import { Label } from './ui/label'
import { TableCell, TableRow } from './ui/table'

/** 상태·결과 code Badge: 한국어 이름 + 원래 code는 title(마우스 올리면 보임). 색은 의미(성공·대기·실패·종료)로만. */
export function StatusBadge({ code }: { code: string }) {
  return (
    <Badge variant={statusTone(code)} title={code}>
      {labelOf(code)}
    </Badge>
  )
}

/** 역할 목록 Badge(한국어 + title 원래 code). 정지 역할은 취소선, 없으면 '-'. */
export function RoleBadges({ roles, suspended = false }: { roles: string[]; suspended?: boolean }) {
  if (roles.length === 0) return <span className="text-muted-foreground">-</span>
  return (
    <span className="flex flex-wrap gap-1">
      {roles.map((role) => (
        <Badge key={role} variant={suspended ? 'outline' : 'secondary'} title={role} className={suspended ? 'text-muted-foreground line-through' : undefined}>
          {labelOf(role)}
        </Badge>
      ))}
    </span>
  )
}

/** 종류 code: 한국어 이름 위, 원래 code는 작은 mono 글씨로 아래. */
export function KindLabel({ code }: { code: string }) {
  return (
    <span className="flex flex-col gap-0.5">
      <span className="font-medium">{labelOf(code)}</span>
      <span className="font-mono text-[11px] text-muted-foreground">{code}</span>
    </span>
  )
}

/** ISO 시각을 KST로(원문 ISO는 title). */
export function KstTime({ iso }: { iso: string }) {
  return (
    <time dateTime={iso} title={iso} className="whitespace-nowrap">
      {formatKst(iso)}
    </time>
  )
}

/** 빈 목록 안내 행. */
export function EmptyRow({ columns, message }: { columns: number; message: string }) {
  return (
    <TableRow className="hover:bg-transparent">
      <TableCell colSpan={columns} className="h-28 text-center text-muted-foreground">
        <span className="flex flex-col items-center gap-2">
          <InboxIcon aria-hidden="true" className="size-6 opacity-50" />
          {message}
        </span>
      </TableCell>
    </TableRow>
  )
}

/** 제출 중 표시(아이콘만 — 버튼 이름은 그대로). */
export function Spinner({ active }: { active: boolean }) {
  return active ? <LoaderCircleIcon aria-hidden="true" className="animate-spin" /> : null
}

/**
 * 입력 칸: 위 label(입력을 감싸 접근성 이름 유지) + 바로 아래 도움말·오류(aria-describedby 연결). 도움말은 label 밖에 두어 label 이름을 바꾸지 않는다.
 */
export function Field({ label, hint, error, className, children }: {
  label: string
  hint?: ReactNode
  error?: ReactNode
  className?: string
  children: (describedBy: string | undefined) => ReactNode
}) {
  const id = useId()
  const describedBy = error || hint ? `${id}-desc` : undefined
  return (
    <div className={['flex flex-col gap-1.5', className].filter(Boolean).join(' ')}>
      <Label>
        <span>{label}</span>
        {children(describedBy)}
      </Label>
      {(error || hint) && (
        <p id={describedBy} className={error ? 'text-xs text-destructive' : 'text-xs text-muted-foreground'}>
          {error ?? hint}
        </p>
      )}
    </div>
  )
}
