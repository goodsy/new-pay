import { useState } from 'react'
import { Button } from '../components/ui/Button'
import { DemoFootnote, DetailGrid, FormRow, Notice, Timeline } from '../components/ui/Panel'
import { Pipeline } from '../components/ui/Pipeline'
import { StatusBadge } from '../components/ui/Tag'
import { pageTitle } from '../data/menus'
import { MODULES, SETTLE_PIPELINE } from '../data/modules'
import type { ModuleId, Row } from '../data/types'
import { useWorkspace } from '../store/WorkspaceProvider'

/**
 * 행 상세 Drawer(원본 detail()). 공통 헤더(eyebrow · 이름 · 식별키+상태 · 2열 상세) 아래에 모듈별 본문이 붙는다.
 * merchant: 서비스 개통 타임라인 / payment: 거래 타임라인 / settle: 정산 단계+승인요청(시연)
 * source: 채번·연결 정보 / 그 외(acquire, risk, report, ops): 처리 기록 + 검토 메모
 * 모든 버튼은 화면 시연이다 — 실제 승인·지급·대사 요청을 보내지 않는다.
 */
export function DetailPanel({ moduleId, rowKey }: { moduleId: ModuleId; rowKey: string }) {
  const { rows } = useWorkspace()
  const r = rows[moduleId].find((x) => x[1] === rowKey)
  if (!r) return <div className="empty">대상을 찾을 수 없어요</div>
  const cols = MODULES[moduleId].cols

  return (
    <>
      <div className="eyebrow">{`${pageTitle(moduleId)} / 상세`}</div>
      <h2>{r[0]}</h2>
      <p className="subtle">
        {`${r[1]}　`}
        <StatusBadge status={r[4]} />
      </p>
      <DetailGrid items={cols.slice(1).map((label, i) => ({ label, value: r[i + 2] }))} />
      <ModuleBody moduleId={moduleId} r={r} />
      <DemoFootnote />
    </>
  )
}

function ModuleBody({ moduleId, r }: { moduleId: ModuleId; r: Row }) {
  const { go } = useWorkspace()
  switch (moduleId) {
    case 'merchant':
      return (
        <>
          <h3>서비스 개통 현황</h3>
          <Timeline
            items={[
              { title: '사업자·계약 등록', desc: '사업자와 계약을 기준으로 MID를 연결합니다.' },
              { title: '원천사 연결', desc: 'NICE / 키움페이 · 원천사 채번과 내부 MID는 별도 관리' },
              { title: '가맹점 MID 발급', desc: `${r[1]} · GID: G-DEMO01 · VID: V-DEMO01` },
              { title: '수수료·정산정책', desc: '샘플 정책: 카드 2.4% · VAT 별도 · D+3' },
            ]}
          />
          <div className="actions">
            <Button onClick={() => go('payment')}>결제 보기</Button>
            <Button onClick={() => go('settle')}>정산 보기</Button>
          </div>
        </>
      )
    case 'payment':
      return (
        <>
          <h3>거래 타임라인</h3>
          <Timeline
            items={[
              { title: '결제 요청', desc: `${r[3]} · 주문번호 ORD-DEMO-001` },
              { title: r[4], desc: `원천사 TID: SRC-${r[1]}` },
              ...(r[4] === '부분 취소' ? [{ title: '부분취소 16,000원', desc: '원승인 80,000원 · 잔액 64,000원 · 별도 취소 TID 연결' }] : []),
              { title: '매입 / 정산', desc: '처리 결과는 매입·정산 내역에서 확인합니다.' },
            ]}
          />
          <Button onClick={() => go('acquire')}>연결된 매입 업무 →</Button>
        </>
      )
    case 'settle': {
      // 원본 규칙 그대로: 상태에 보류/완료가 있고 '검증'이 없으면 비활성(지급 보류, 지급 완료). 시연 버튼 — 실제 승인 통제는 서버 책임.
      const disabled = /보류|완료/.test(r[4]) && !r[4].includes('검증')
      return (
        <>
          <h3>정산 처리 단계</h3>
          <Pipeline steps={SETTLE_PIPELINE} />
          <Notice>승인자 분리, 보류 제외, 중복 지급 방지는 실제 시스템 연동 시 서버에서 검증해야 합니다. 이 화면은 요청 흐름 시연입니다.</Notice>
          <ReviewForm label="검토 사유" placeholder="검증 결과와 요청 사유 입력" submitLabel="지급 승인 요청 시연" disabled={disabled} />
        </>
      )
    }
    case 'source':
      return (
        <>
          <h3>채번 및 연결 정보</h3>
          <Notice>원천사 관리코드 → 원천사 가맹점번호 → 내부 MID 매핑을 구분합니다. 발급 주체와 번호 체계는 원천사별 확인이 필요합니다.</Notice>
          <DetailGrid
            items={[
              { label: '연결 내부 MID', value: 'mono000001' },
              { label: '사용 환경', value: '테스트' },
            ]}
          />
          <Button onClick={() => go('merchant')}>연결 가맹점 보기 →</Button>
        </>
      )
    default:
      return (
        <>
          <h3>처리 기록</h3>
          <Timeline
            items={[
              { title: '대상 수집', desc: '2026.11.02 09:00 · 샘플 실행 이력' },
              { title: r[4], desc: '차이 내역과 원천 데이터를 확인 후 처리합니다.' },
            ]}
          />
          <ReviewForm label="검토 메모" placeholder="검토 내용 입력" submitLabel="검토 기록 남기기" />
        </>
      )
  }
}

/** 사유 입력(#reason) + primary 버튼(원본 submitReview). 빈 값이면 토스트로 막고, 입력하면 토스트 후 닫는다 — 기록은 시연(저장·요청 없음) */
function ReviewForm(props: { label: string; placeholder: string; submitLabel: string; disabled?: boolean }) {
  const { toast, closeOverlay } = useWorkspace()
  const [reason, setReason] = useState('')
  const submit = () => {
    if (!reason.trim()) {
      toast('검토 사유를 입력해 주세요')
      return
    }
    toast('데모 검토 이력을 기록했어요')
    closeOverlay()
  }
  return (
    <>
      <FormRow label={props.label} htmlFor="reason">
        <input id="reason" placeholder={props.placeholder} value={reason} onChange={(e) => setReason(e.target.value)} />
      </FormRow>
      <Button variant="primary" disabled={props.disabled} onClick={submit}>
        {props.submitLabel}
      </Button>
    </>
  )
}
