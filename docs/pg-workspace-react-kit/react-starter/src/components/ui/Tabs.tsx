interface Props {
  items: string[];
  active: number;
  /** 인덱스 클릭. 특정 탭이 패널을 여는 경우(계정·권한) onSelect 에서 분기 */
  onSelect: (index: number) => void;
}

/** .tabs — 카드 상단 밑줄 탭. 가로 스크롤 허용(white-space: nowrap) */
export function Tabs({ items, active, onSelect }: Props) {
  return (
    <div className="tabs" role="tablist">
      {items.map((t, i) => (
        <button
          type="button"
          role="tab"
          aria-selected={i === active}
          key={t}
          className={i === active ? 'on' : ''}
          onClick={() => onSelect(i)}
        >
          {t}
        </button>
      ))}
    </div>
  );
}
