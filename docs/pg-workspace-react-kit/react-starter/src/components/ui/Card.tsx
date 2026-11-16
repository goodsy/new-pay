import type { ReactNode } from 'react';

/** .card — 흰 배경, 1px line, radius 13px, overflow hidden. 내부에 CardTop/Tabs/Filters/DataTable 을 쌓는다 */
export function Card({ children, as = 'section' }: { children: ReactNode; as?: 'section' | 'div' }) {
  const Tag = as;
  return <Tag className="card">{children}</Tag>;
}

interface CardTopProps {
  title: ReactNode;
  /** 제목 옆 파란 카운트 칩(.count) */
  count?: number;
  /** 우측 텍스트 링크 버튼 (예: "운영 현황 →") */
  linkLabel?: string;
  onLink?: () => void;
}

/** .cardtop — 카드 헤더 (h2 17px) */
export function CardTop({ title, count, linkLabel, onLink }: CardTopProps) {
  return (
    <div className="cardtop">
      <h2>
        {title}
        {count !== undefined && (
          <>
            {' '}
            <span className="count">{count}</span>
          </>
        )}
      </h2>
      {linkLabel && (
        <button type="button" onClick={onLink}>
          {linkLabel}
        </button>
      )}
    </div>
  );
}
