import { useCallback, useEffect, useState } from 'react'
import type { AdminApi, ApprovalView } from '../api/adminApi'
import { OneTimeCode } from '../components/OneTimeCode'
import { OwnerApprovalDetail } from '../components/OwnerApprovalDetail'
import { isRegistrant, MerchantContractApprovalDetail } from '../components/MerchantContractApprovalDetail'
import { ReacquisitionApprovalDetail } from '../components/ReacquisitionApprovalDetail'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { Field } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { Input } from '../components/ui/input'
import { labelOf } from '../lib/labels'
import { MERCHANT_LABELS_UNAVAILABLE } from '../lib/merchantLabel'
import { anyLabelUnavailable, merchantTargetLabel, merchantTargetText } from '../lib/merchantTarget'
import { useMerchantTargetLabels } from '../lib/useMerchantTargetLabels'
import { tagClass } from '../lib/statusTone'

const TOTP = /^[0-9]{6}$/

interface Props {
  api: AdminApi
  view: 'pending' | 'mine'
  /** 승인·거절 버튼 표시(APPROVER — 표시만, 권한 판정은 서버). */
  canDecide: boolean
  /** 조회자(근거·사유 버튼 표시 판단용 — 표시만, 권한 판정은 서버). */
  viewer?: { actorId: string; roles: string[]; demoQuickLogin?: boolean }
  onSessionEnded?: (code: ConsoleErrorCode) => void
}

function Row({ item, target, canDecide, mine, registrant, selected, stepUpWaived = false, onApprove, onReject, onWithdraw, onDetail }: {
  item: ApprovalView
  /** 대상 표시 텍스트(MERCHANT-CODE-001 §5.6.2 — 가맹점 = "<상호> · <코드>"/"가맹점 정보 없음"/"—", 내부 키 없음). */
  target: string
  canDecide: boolean
  /** 이 승인의 상세가 열려 있음(표시만). */
  selected: boolean
  /** 조회자가 이 계약 Revision의 등록자(MERCHANT-OPS-002 D-MOPS-008 ③ — 표시만, 서버가 403 APPROVER_IS_REGISTRANT로 다시 막는다). */
  registrant: boolean
  mine: boolean
  /** ADMIN-DEMO-LOGIN-001 결정 ③: 데모 간편 로그인 세션 — TOTP 입력 대신 안내, 빈 코드로 보낸다(생략 판정은 서버, 일반 세션은 기존 그대로). */
  stepUpWaived?: boolean
  onApprove: (id: string, totp: string) => Promise<void>
  onReject: (id: string, totp: string) => Promise<void>
  onWithdraw: (id: string) => Promise<void>
  onDetail: (id: string) => Promise<void>
}) {
  const [totp, setTotp] = useState('')
  const valid = stepUpWaived || TOTP.test(totp)
  const decide = async (action: (id: string, totp: string) => Promise<void>) => {
    const code = stepUpWaived ? '' : totp
    setTotp('')
    await action(item.approvalId, code)
  }
  // ADMIN-UI-WORKSPACE-001(승인함): 시안 표 칸 — 종류는 strong 이름 + small code, 역할은 .tag(title = code), 상태는 tagClass .tag(title = code), 버튼은 .btn(승인만 .btn.primary).
  // 인증 코드 입력은 도움말 aria-describedby 연결을 지키려고 기존 Field·Input 그대로(거래 조회·재매입 요청 양식 선례). 표시·차단 조건(canDecide·registrant·mine·targetKind)은 그대로.
  return (
    // reviewFix1 NIT-1: 상세를 연 행은 기존 tr.selected(거래 조회와 같음). 초점 이동은 변경 전 코드에도 없어 그대로 둔다.
    <tr aria-label={`${item.kind} ${target} ${item.status}`} className={selected ? 'selected' : undefined}>
      {/* Coordinator 캡처(1101·1024px — 표가 감싸는 칸보다 44~60px 넓어 작업 열이 가려짐): 긴 종류 code는 칸 안에서 줄을 바꾼다(기존 utility만). */}
      <td className="whitespace-normal">
        <strong>{labelOf(item.kind)}</strong>
        <small className="break-all">{item.kind}</small>
      </td>
      <td>
        {item.grantedRole ? <span className="tag" title={item.grantedRole}>{labelOf(item.grantedRole)}</span> : <span className="font-mono">{item.causeApprovalId ?? ''}</span>}
      </td>
      <td className="font-mono">{target}</td>
      <td>{item.requestedByDisplayName}</td>
      <td><span className={tagClass(item.status)} title={item.status}>{labelOf(item.status)}</span></td>
      <td>
        {/* reviewFix1 MINOR-2(1101px 캡처 — 인증 코드 칸·도움말이 표 오른쪽 끝 밖으로 잘림): 결정 칸을 세로로 쌓는다 — 인증 코드 칸(기존 w-44, 도움말은 표의 nowrap 대신 줄바꿈) 위,
            버튼 줄(승인·거절·철회·상세 — 기존 DOM 순서 그대로) 아래. 새 크기 값 없음. */}
        <div className="flex flex-col items-start gap-2">
          {canDecide && item.status === 'PENDING' && stepUpWaived && (
            <span className="subtle whitespace-normal text-xs" role="note" aria-label="재인증 생략">데모: 재인증 생략</span>
          )}
          {canDecide && item.status === 'PENDING' && !stepUpWaived && (
            <Field label="인증 코드(6자리)" hint={valid ? undefined : '6자리를 입력하면 승인·거절할 수 있습니다.'} className="w-36 whitespace-normal text-xs">
              {(describedBy) => (
                <Input inputMode="numeric" maxLength={6} value={totp} onChange={(e) => setTotp(e.target.value)} autoComplete="one-time-code" aria-describedby={describedBy} className="h-8 font-mono tracking-widest" />
              )}
            </Field>
          )}
          <div className="flex flex-wrap items-center gap-2">
            {canDecide && item.status === 'PENDING' && (
              <>
                {registrant ? (
                  <span className="subtle">등록자는 승인할 수 없습니다</span>
                ) : (
                  <button type="button" className="btn primary" disabled={!valid} onClick={() => decide(onApprove)}>
                    승인
                  </button>
                )}
                {/* reviewFix1 MINOR-1: 거절은 콘솔의 다른 파괴 동작 버튼(shadcn destructive)과 같은 token — destructive-solid 바탕·선 + 흰 글자(다크 #b91c1c 6.47:1). NIT-3: 구분선 제거. */}
                <button type="button" className="btn border-destructive-solid bg-destructive-solid text-white" disabled={!valid} onClick={() => decide(onReject)}>
                  거절
                </button>
              </>
            )}
            {mine && item.status === 'PENDING' && (
              <button type="button" className="btn" onClick={() => onWithdraw(item.approvalId)}>
                철회
              </button>
            )}
            {(item.targetKind === 'PAYMENT' || item.targetKind === 'ACQUISITION_FILE' || item.targetKind === 'MERCHANT') && (
              <button type="button" className="btn" onClick={() => onDetail(item.approvalId)}>
                상세
              </button>
            )}
          </div>
        </div>
      </td>
    </tr>
  )
}

/** 승인함(PENDING — 승인·거절 + 직전 TOTP)과 내 요청(철회). 재설정 승인 응답의 코드는 [OneTimeCode]로 1회만 보이고 닫으면 상태에서 지운다. */
export function ApprovalsScreen({ api, view, canDecide, viewer, onSessionEnded }: Props) {
  const [items, setItems] = useState<ApprovalView[]>([])
  const [detail, setDetail] = useState<ApprovalView | null>(null)
  const [error, setError] = useState<ConsoleErrorCode | null>(null)
  const [oneTimeCode, setOneTimeCode] = useState<string | null>(null)

  const fail = useCallback((e: unknown) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) onSessionEnded(code)
    else setError(code)
  }, [onSessionEnded])

  const load = useCallback(async () => {
    try {
      setItems((await api.approvals(view)).approvals)
    } catch (e) {
      fail(e)
    }
  }, [api, view, fail])

  useEffect(() => {
    void load()
  }, [load])

  /** 승인·거절·철회 후 목록을 다시 읽고, 그 승인이 상세에 열려 있으면 상세도 다시 읽는다(ACQ-ADMIN-001 B2 리뷰 m-1 — 낡은 PENDING 상세가 비교를 계속하지 않게). */
  const act = async (id: string, action: () => Promise<{ enrollmentCode?: string } | object>) => {
    setError(null)
    try {
      const result = await action()
      if ('enrollmentCode' in result && typeof result.enrollmentCode === 'string') setOneTimeCode(result.enrollmentCode)
      await load()
      if (detail?.approvalId === id) setDetail(await api.approvalDetail(id))
    } catch (e) {
      fail(e)
    }
  }

  // ADMIN-UI-WORKSPACE-001(승인함·내 요청): 대사 발견(96608d3)과 같이 MenuCard flush 카드 바로 안에 시안 구성 — .mapnote(h2 화면 이름 + 설명) → (오류 시) .cardnote
  // → .tablewrap(빈 목록 .empty) → .tablebottom(표시 건수) → (상세를 열면) .detailarea(eyebrow·h3 승인 상세 + small 승인 ID, 종류별 상세 카드). 조회 조건이 없어 .filters 줄은 없다.
  // 등록 코드 1회 표시([OneTimeCode])는 body에 붙는 dialog라 카드 구조에 들어가지 않는다(그대로).
  // MERCHANT-CODE-001 §5.6.2·§5.6.3(MC-3c): 목록을 읽을 때마다 가맹점 대상 라벨을 고유 키 정렬 묶음으로 한 번 부른다(0개면 미호출). 실패·429는 "—" + 표 아래 안내 1줄.
  const labels = useMerchantTargetLabels(api, items)
  const name = view === 'pending' ? '승인함' : '내 요청'
  return (
    <>
      <div className="mapnote">
        <h2>{name}</h2>
        {' · '}
        <span>{view === 'pending' ? '다른 관리자가 요청한 작업을 검토하고, 인증 코드로 승인하거나 거절합니다.' : '내가 만든 승인 요청의 진행 상태를 확인하고, 대기 중이면 철회합니다.'}</span>
      </div>
      {error && (
        <div className="cardnote">
          <ErrorNotice>{ERROR_MESSAGES[error]}</ErrorNotice>
        </div>
      )}
      {oneTimeCode && <OneTimeCode code={oneTimeCode} onClose={() => setOneTimeCode(null)} />}
      <div className="tablewrap">
        <table aria-label={view === 'pending' ? '승인 대기 목록' : '내 승인 요청 목록'}>
          <thead>
            <tr>
              <th>종류</th><th>역할·원인</th><th>대상</th><th>요청자</th><th>상태</th><th>작업</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <Row
                key={item.approvalId}
                item={item}
                target={merchantTargetText(item, labels)}
                canDecide={canDecide && view === 'pending'}
                registrant={isRegistrant(item, viewer?.actorId)}
                stepUpWaived={viewer?.demoQuickLogin === true}
                selected={detail?.approvalId === item.approvalId}
                mine={view === 'mine'}
                onApprove={(id, totp) => act(id, () => api.approve(id, totp))}
                onReject={(id, totp) => act(id, () => api.reject(id, totp))}
                onWithdraw={(id) => act(id, () => api.withdraw(id))}
                onDetail={async (id) => {
                  setError(null)
                  try {
                    setDetail(await api.approvalDetail(id))
                  } catch (e) {
                    fail(e)
                  }
                }}
              />
            ))}
          </tbody>
        </table>
        {items.length === 0 && <div className="empty">{view === 'pending' ? '승인 대기 중인 요청이 없습니다.' : '내가 만든 승인 요청이 없습니다.'}</div>}
        {anyLabelUnavailable(items, labels) && <p role="note" className="mapnote">{MERCHANT_LABELS_UNAVAILABLE}</p>}
      </div>
      <div className="tablebottom">
        <span>{`표시 ${items.length}건`}</span>
      </div>
      {detail && (
        <div className="detailarea">
          <div>
            <div className="eyebrow" aria-hidden="true">{`${name} / 상세`}</div>
            <h3>
              {'승인 상세 '}
              <small>{detail.approvalId}</small>
            </h3>
          </div>
          {detail.targetKind === 'ACQUISITION_FILE' && <ReacquisitionApprovalDetail key={detail.approvalId} api={api} item={detail} viewer={viewer} onSessionEnded={onSessionEnded} />}
          {detail.targetKind === 'MERCHANT' && (
            <MerchantContractApprovalDetail key={detail.approvalId} api={api} item={detail} currentLabel={merchantTargetLabel(detail, labels)} viewer={viewer} onSessionEnded={onSessionEnded} />
          )}
          {detail.targetKind !== 'ACQUISITION_FILE' && detail.targetKind !== 'MERCHANT' && <OwnerApprovalDetail key={detail.approvalId} api={api} item={detail} viewer={viewer} onSessionEnded={onSessionEnded} />}
        </div>
      )}
    </>
  )
}
