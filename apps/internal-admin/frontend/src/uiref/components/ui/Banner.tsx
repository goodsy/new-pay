import type { ReactNode } from 'react'

interface Props {
  /** 좌측 36px 흰색 원형 심볼 문자 (✓, ₩ 등) */
  symbol: ReactNode
  title: ReactNode
  description?: ReactNode
  actionLabel?: string
  onAction?: () => void
}

/** .banner — 연파랑 안내 배너. 모바일에서는 설명(p) 숨김 */
export function Banner({ symbol, title, description, actionLabel, onAction }: Props) {
  return (
    <div className="banner">
      <span className="symbol">{symbol}</span>
      <div>
        <b>{title}</b>
        {description && <p>{description}</p>}
      </div>
      {actionLabel && (
        <button type="button" onClick={onAction}>
          {actionLabel}
        </button>
      )}
    </div>
  )
}
