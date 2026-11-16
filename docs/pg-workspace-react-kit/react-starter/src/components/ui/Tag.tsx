import type { ButtonHTMLAttributes, ReactNode } from 'react';
import { statusTone, type Tone } from '../../lib/status';

const cls = (tone: Tone) => (tone === 'default' ? 'tag' : `tag ${tone}`);

/** .tag — 색상 직접 지정 */
export function Tag({ tone = 'default', children }: { tone?: Tone; children: ReactNode }) {
  return <span className={cls(tone)}>{children}</span>;
}

/** .tag — 상태 문자열로 색상 자동 결정 (원본 badge()) */
export function StatusBadge({ status }: { status: string }) {
  return <Tag tone={statusTone(status)}>{status}</Tag>;
}

/** 버튼형 태그 (메뉴 개편 지도의 "개편 후 업무" 링크) */
export function TagButton({
  tone = 'blue',
  ...rest
}: ButtonHTMLAttributes<HTMLButtonElement> & { tone?: Tone }) {
  return <button type="button" className={cls(tone)} {...rest} />;
}
