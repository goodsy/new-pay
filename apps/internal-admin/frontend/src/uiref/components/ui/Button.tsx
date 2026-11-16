import type { ButtonHTMLAttributes } from 'react'

type Props = ButtonHTMLAttributes<HTMLButtonElement> & { variant?: 'default' | 'primary' }

/** .btn / .btn.primary — 높이 약 38px, radius 8px. disabled 시 opacity .4 */
export function Button({ variant = 'default', className, type = 'button', ...rest }: Props) {
  const cn = ['btn', variant === 'primary' ? 'primary' : '', className ?? ''].filter(Boolean).join(' ')
  return <button type={type} className={cn} {...rest} />
}
