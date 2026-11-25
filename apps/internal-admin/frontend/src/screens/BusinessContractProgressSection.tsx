import type { ContractIntakeView, ContractProgress } from '../api/adminApi'
import { KstTime } from '../components/DataBits'
import { DetailRow as Row } from '../components/DetailCard'
import { WarningNote } from '../components/Notice'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import {
  contractManager, DESIRED_SERVICE_NOTE, desiredServices, intakeChannel, intakeSummary, MIGRATED, transitionActor, transitionChange, transitionEvidence,
} from '../lib/contractProgress'

/**
 * 사업자 상세 '계약 진행' 섹션(MERCHANT-FLOW-001 §6 FLOW-1 — **읽기 전용**: 생성·전이 명령은 FLOW-2). 접수 건(최신부터)마다 요약·접수 항목·전이 이력 표.
 * - R-1: 영업 계약 진행 기록이다 — 결제 허용·MID 발급은 가맹점 계약 승인(2인)으로 정한다는 안내를 늘 보인다.
 * - 근거 참조 원문은 사업자 조회 역할 전체에 보인다(D-FLOW-004 ④ — 열람은 서버의 상세 열람 감사 1행에 포함, 화면 별도 조회 없음).
 * - 계약 담당자·처리자는 ADMIN이 채운 표시명(m-11). 내부 키·로그인 이름·version은 그리지 않는다. 섹션을 불러오지 못하면 섹션만 안내(fail-safe).
 */
export function BusinessContractProgressSection({ progress }: { progress: ContractProgress | undefined }) {
  return (
    <section aria-label="계약 진행" className="flex flex-col gap-3 rounded-xl border bg-card px-4 py-3">
      <div className="flex flex-col gap-1">
        <h3 className="text-sm font-semibold">계약 진행</h3>
        <p className="text-xs text-muted-foreground">영업 계약 진행 기록입니다. 결제 허용·MID 발급은 가맹점 계약 승인으로 정합니다.</p>
      </div>
      {!progress?.available ? (
        <WarningNote>계약 진행을 불러오지 못했습니다. 사업자 상세를 다시 열어 확인하세요.</WarningNote>
      ) : progress.intakes.length === 0 ? (
        <p className="text-sm text-muted-foreground">계약 접수 건이 없습니다.</p>
      ) : (
        progress.intakes.map((intake) => <Intake key={intake.intakeSeq} intake={intake} />)
      )}
    </section>
  )
}

function Intake({ intake }: { intake: ContractIntakeView }) {
  const name = `계약 접수 ${intake.intakeSeq}`
  return (
    <div role="group" aria-label={name} className="flex flex-col gap-2 border-t pt-3">
      <h4 className="text-sm font-semibold">{name}</h4>
      <dl>
        <Row term="진행 상태"><span>{intakeSummary(intake)}</span></Row>
        <Row term="접수일">{intake.migrated ? MIGRATED : (intake.receivedOn ?? '-')}</Row>
        <Row term="접수 경로">{intakeChannel(intake)}</Row>
        <Row term="계약 담당자">{contractManager(intake)}</Row>
        <Row term="희망 서비스">
          <span>{desiredServices(intake)}</span>
          {!intake.migrated && <span className="text-xs text-muted-foreground">{DESIRED_SERVICE_NOTE}</span>}
        </Row>
      </dl>
      <Table aria-label={`${name} 진행 이력`}>
        <TableHeader>
          <TableRow>
            <TableHead>순번</TableHead><TableHead>변경</TableHead><TableHead>근거 참조</TableHead><TableHead>처리자</TableHead><TableHead>시각</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {intake.transitions.map((t) => (
            <TableRow key={t.transitionSeq}>
              <TableCell>{t.transitionSeq}</TableCell>
              <TableCell>{transitionChange(t)}</TableCell>
              <TableCell><span className="font-mono text-xs break-all">{transitionEvidence(t)}</span></TableCell>
              <TableCell>{transitionActor(t)}</TableCell>
              <TableCell><KstTime iso={t.occurredAt} /></TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    </div>
  )
}
