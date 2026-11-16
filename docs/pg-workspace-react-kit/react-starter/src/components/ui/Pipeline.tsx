import { StatusBadge } from './Tag';

/** [단계번호/✓, 제목, 보조설명, 상태] */
export type PipelineStep = [string, string, string, string];

/** .pipeline > .pipe — 단계 진행 리스트 (원형 번호 + 제목/설명 + 우측 상태 태그) */
export function Pipeline({ steps }: { steps: PipelineStep[] }) {
  return (
    <div className="pipeline">
      {steps.map((r) => (
        <div className="pipe" key={r[1]}>
          <span className="stepno">{r[0]}</span>
          <div>
            <b>{r[1]}</b>
            <small>{r[2]}</small>
          </div>
          <StatusBadge status={r[3]} />
        </div>
      ))}
    </div>
  );
}
