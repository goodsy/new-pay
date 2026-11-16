import type { ReactNode } from 'react';

interface Props {
  title: ReactNode;
  description: ReactNode;
  /** 우측 버튼 영역(.actions). 모바일(≤720px)에서는 세로 스택 */
  actions?: ReactNode;
  eyebrow?: string;
}

/** .heading — 모든 페이지 최상단. eyebrow(11px 대문자) → h1(28px) → 설명(12px) */
export function PageHeading({ title, description, actions, eyebrow = 'PAYMENT OPERATIONS' }: Props) {
  return (
    <div className="heading">
      <div>
        <div className="eyebrow">{eyebrow}</div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      <div className="actions">{actions}</div>
    </div>
  );
}
