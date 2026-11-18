import type { ComponentProps } from 'react'
import { cn } from '../lib/utils'
import { Button } from './ui/button'

/**
 * 버튼 하나를 두 모양으로(ADMIN-UI-WORKSPACE-001 — 등록 화면 WS8): [workspace]이면 참고 시안 버튼(.btn, 주 버튼 .btn.primary, 위험 버튼은 기존
 * destructive-solid token 위 흰 글자 — 승인함 거절·계정 정지 선례), 아니면 기존 shadcn Button 그대로(아직 옮기지 않은 화면 — 사업자 조회 상세 수정 폼·가맹점 조회 계약 Revision 폼).
 * 이름·type·비활성·aria 속성은 호출자가 준 그대로 넘긴다. workspace 모양에서 [size]는 쓰지 않는다(시안 .btn 크기 하나 — 리뷰 NIT-4).
 * workspace 모양의 type 기본값은 "button"(form 안에서 뜻하지 않게 제출하지 않게 — 리뷰 NIT-4, 호출자가 준 type이 이긴다).
 * shadcn Button처럼 글자 줄바꿈·flex 줄어듦을 막는다(whitespace-nowrap shrink-0 — 리뷰 NIT-3).
 */
type Variant = 'default' | 'outline' | 'ghost' | 'destructive'

export function ActionButton({ workspace, variant = 'default', size, className, type, ...props }: ComponentProps<'button'> & {
  workspace: boolean
  variant?: Variant
  size?: 'default' | 'sm'
}) {
  if (!workspace) return <Button variant={variant} size={size} className={className} type={type} {...props} />
  return (
    <button
      type={type ?? 'button'}
      className={cn('btn', 'whitespace-nowrap shrink-0', variant === 'default' && 'primary', variant === 'destructive' && 'border-destructive-solid bg-destructive-solid text-white', className)}
      {...props}
    />
  )
}
