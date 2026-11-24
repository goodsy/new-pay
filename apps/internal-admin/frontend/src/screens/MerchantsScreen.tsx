import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, MerchantDetail, MerchantHistoryEntry, MerchantListItem, MerchantSearchInput } from '../api/adminApi'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import { DetailRow } from '../components/DetailCard'
import { EmptyRow, KindLabel, KstTime, Spinner, StatusBadge } from '../components/DataBits'
import { ErrorNotice } from '../components/Notice'
import { BackToListButton, MapNote } from '../components/MapNote'
import { ContractRevisionForm, useRevisionLocked } from '../components/ContractRevisionForm'
import { ContractApprovalRequest } from '../components/ContractApprovalRequest'
import { FirstMidRequest, mayRequestFirstMid } from '../components/FirstMidRequest'
import { MerchantCodeChangeRequest, mayRequestCodeChange } from '../components/MerchantCodeChangeRequest'
import { APPROVAL_GROUPS, MerchantActivationArea, pendingIn, SiteReviewTable } from '../components/MerchantActivationArea'
import { ServiceOpeningTimeline } from '../components/ServiceOpeningTimeline'
import { createScreenMemory, useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { isRegistrationNumber } from '../lib/registrationNumber'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { ChevronDownIcon } from '../components/icons'
import { formatMinor } from '../lib/format'
import { historyTarget, historyTargetLabel } from '../lib/historyTarget'
import { changeTypeLabel } from '../lib/merchantChangeTypes'
import { labelOf, REVISION_AWAITING_APPROVAL } from '../lib/labels'
import { MERCHANT_CODE, MERCHANT_CODE_HINT } from '../lib/merchantCode'

interface Props {
  api: AdminApi
  /** 사업자등록번호 입력란 표시(OPERATOR·APPROVER — §5.3, 표시만; 권한 판정·감사는 서버). */
  canSearchRegistrationNumber: boolean
  onSessionEnded?: (code: ConsoleErrorCode) => void
  /** 화면을 열면서 이 가맹점 상세를 연다(가맹점 등록 "가맹점 조회에서 보기" — 행 "상세"와 같은 경로·감사, MERCHANT-OPS-002 §5.2). */
  openMerchantId?: string | null
  /** [openMerchantId]를 한 번 연 뒤 알린다(호출자가 비워 탭을 다시 와도 또 열지 않게). */
  onOpenedMerchant?: () => void
  /** 제목 줄 '＋ 가맹점 등록'(D-ADMIN-UI-006 — 같은 탭 안 등록 화면). 호출자가 이 역할에게 등록 화면이 보일 때만(OPERATOR — pagesFor) 준다. 없으면 버튼 없음. */
  onRegister?: () => void
  /** 계약 영역 "새 Revision 등록" 표시(OPERATOR — MERCHANT-OPS-002 §9.6 B2-1, 표시만; 판정·감사는 서버). */
  canRegisterRevision?: boolean
  /** Revision 폼 상태(결과 불명 잠금·submissionId·입력)를 둘 App 메모리(리뷰 C-M1 — 탭·가맹점 전환에도 유지, 세션 경계에서 App이 새로 만든다). 없으면 화면 자체 메모리. */
  revisionMemory?: ScreenMemory
  /** Revision 행 "승인 요청" 표시(OPERATOR — MERCHANT-OPS-002 §9.6 B2-2, 미승인·끝나지 않은 Revision, 승인 대기 없음; 표시만 — 판정·감사는 서버). */
  canRequestApproval?: boolean
  /** 승인 요청 성공 뒤 "승인함에서 보기". */
  onOpenApprovals?: () => void
  /**
   * 화면 상태(MERCHANT-DETAIL-VIEW-001 — 목록 ↔ 상세 화면 전환, 검색 조건·목록 결과·다음 쪽 커서·연 상세)를 둘 App 메모리(사업자 조회 `businessesMemory`와 같은 규칙 —
   * 탭·메뉴를 오가도 유지, 세션 경계에서 App이 새로 만든다). 사업자등록번호는 담지 않는다. 없으면 화면 자체 메모리(다시 그리면 목록부터).
   */
  memory?: ScreenMemory
  /** 시험 주입용(끝난 Revision 판정 기준 시각). */
  now?: () => Date
  /** 시험 주입용(Revision 폼 submissionId·오늘 KST). */
  newSubmissionId?: () => string
  todayKst?: () => string
}

/** 마지막으로 보낸 검색 조건(다음 쪽에 그대로). 사업자등록번호는 담지 않는다 — 화면 상태에 번호를 남기지 않는다. */
interface SentSearch {
  criteria: Omit<MerchantSearchInput, 'registrationNumber' | 'after'>
  byRegistrationNumber: boolean
}

/** 목록 화면 또는 가맹점 하나의 상세 화면(MERCHANT-DETAIL-VIEW-001 — 사업자 조회 View와 같은 관례, URL·hash 없음). 내부 키(mer_)는 메모리에만 — DOM에 없다. */
type View = { kind: 'list' } | { kind: 'detail'; merchantId: string }
type ListPage = { items: MerchantListItem[]; nextCursor: string | null }
type HistoryPage = { items: MerchantHistoryEntry[]; nextCursor: number | null }

const LIST: View = Object.freeze({ kind: 'list' })
const VIEW_KEY = 'lookup.view'
const HISTORY_KEY = 'lookup.history'

/**
 * 가맹점 조회 탭을 목록으로(사업자 조회 [showBusinessListIn]과 같은 규칙 — 등록 화면 '목록으로'·등록 화면에서 부모 탭 클릭). 기억한 화면('view')만 바꾼다 —
 * 검색 조건·결과·상세, 결과 불명 잠금 메모리(revisionMemory)는 그대로.
 */
export function showMerchantListIn(memory: ScreenMemory) {
  memory.write(VIEW_KEY, LIST)
}

/**
 * 요청 순번(목록·상세) — 같은 화면 메모리면 화면을 다시 그려도 같은 순번을 쓴다. 화면이 사라진 동안 도착한 앞 요청의 응답은 메모리에 쓰이지만(다시 그린 화면이 반영),
 * 그 뒤 새로 연 상세·새 검색 결과를 덮지는 못한다(리뷰 MINOR-4와 같은 규칙을 메모리 범위로).
 */
const requestCounters = new WeakMap<ScreenMemory, { list: { current: number }; open: { current: number } }>()
function requestsOf(memory: ScreenMemory) {
  let counters = requestCounters.get(memory)
  if (counters === undefined) {
    counters = { list: { current: 0 }, open: { current: 0 } }
    requestCounters.set(memory, counters)
  }
  return counters
}

const Mono = ({ children }: { children: ReactNode }) => <span className="font-mono text-xs break-all">{children}</span>
const period = (from: string, to: string | null) => (
  <span className="flex flex-col gap-0.5 text-xs">
    <KstTime iso={from} />
    {to === null ? <span className="text-muted-foreground">종료일 없음</span> : <KstTime iso={to} />}
  </span>
)

/** 한도 값(설정값 — 금융 사실 아님). 무제한은 이름, 금액은 안전 정수일 때만 천 단위 표시(짐작하지 않는다 — S8 리뷰 L-2와 같은 이유). */
function limitText(kind: string, amount: number | null, currency: string): string {
  if (kind !== 'LIMITED') return labelOf(kind)
  return amount !== null && Number.isSafeInteger(amount) ? formatMinor(amount, currency) : '표시할 수 없는 금액'
}

/**
 * 상세 패널 하나(`section` + 이름 → region). ADMIN-UI-WORKSPACE-001: 참고 시안 `.card` > `.cardtop`(제목 17px) 모양 — 이름은 접근성 이름 그대로.
 * 제목은 상세 제목(h3) 아래 단계인 h4(시안 `.cardtop h2`와 같은 값 — workspace.css).
 */
function Panel({ name, children }: { name: string; children: ReactNode }) {
  return (
    <section className="card" aria-label={name}>
      <div className="cardtop">
        <h4>{name}</h4>
      </div>
      <div className="cardbody">{children}</div>
    </section>
  )
}

/** "새 Revision 등록" 버튼 — 그 계약의 폼이 결과 불명으로 잠겨 있으면 닫혀 있어도 알린다(리뷰 C-M1). */
function RevisionButton({ memory, contractId, onOpen }: { memory: ScreenMemory; contractId: string; onOpen: () => void }) {
  const locked = useRevisionLocked(memory, contractId)
  return (
    <div className="flex justify-end">
      <Button type="button" variant={locked ? 'destructive' : 'outline'} size="sm" onClick={onOpen}>
        {locked ? '새 Revision 등록 — 결과 확인 필요' : '새 Revision 등록'}
      </Button>
    </div>
  )
}

function DetailPanels({ detail, contractAction, revisionAction, activation, siteReview }: {
  detail: MerchantDetail
  /**
   * 가맹점 단위 Site 심사 표(MERCHANT-IA-001 B1 — 예전에는 MID "개통" 영역마다 같은 표가 반복됐다). Site 패널 안 Site 목록 아래에 한 번 그린다:
   * Site를 보는 곳 바로 옆이 자연스럽고, B4 탭(Site·계약)에서도 같은 자리다. MID가 하나라도 있을 때만 그린다 — 개통 영역 안에 있던 때의 표시 조건 그대로.
   */
  siteReview?: ReactNode
  contractAction?: (contractId: string) => ReactNode
  /** MID 아래 "개통" 영역(B3-1 §9.17 — 결제수단·적용 확인·통화·점검표). 있으면 결제수단 표를 이 영역이 그린다. */
  activation?: (account: MerchantDetail['accounts'][number]) => ReactNode
  /** Revision 행의 작업 칸(B2-2 "승인 요청" — 표시만, 판정은 서버). */
  revisionAction?: (contractId: string, revision: MerchantDetail['contracts'][number]['revisions'][number]) => ReactNode
}) {
  const { merchant, business, sites, contracts, accounts, limits, propagation } = detail
  const pending = detail.pendingApprovals ?? []
  return (
    <>
      {pending.length > 0 && (
        <section role="status" aria-label="승인 대기" className="rounded-lg border border-warning/40 bg-warning/10 p-3 text-sm">
          {pending.map((p) => (
            <p key={p.approvalId} className="flex flex-wrap items-center gap-2">
              <span className="font-medium">승인 대기 중</span>
              {/* Demo 개선 C-2: 대상(계약 승인 Revision 번호 또는 첫 MID 발급). */}
              {/* Demo 지적 1(B2-3): 첫 MID 발급은 종류 라벨이 대상을 말하므로 대상 표시는 계약 승인의 Revision 번호만. */}
              {p.revisionNo != null && <span>{`Revision ${p.revisionNo}`}</span>}
              <KindLabel code={p.kind} /><StatusBadge code={p.status} /><Mono>{p.approvalId}</Mono>
            </p>
          ))}
        </section>
      )}
      <Panel name="기본정보">
        <dl>
          <DetailRow term="가맹점">
            {/* MERCHANT-CODE-001 MC-2: 가맹점 내부 키(mer_…) 대신 가맹점 코드(D-ADMIN-UI-004 ③ — 내부 키는 호출에만). */}
            <span className="flex flex-col"><span className="font-medium">{merchant.name}</span><Mono>{merchant.merchantCode}</Mono></span>
          </DetailRow>
          {/* MERCHANT-IA-001 B2(D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다"): '가맹점 상태'·'사업자 상태'는 해지 경로가 없어 늘 '활성'이라 줄을 뺐다(값·API 그대로, 머리 배지는 B4에서 판단). */}
          <DetailRow term="등록일"><KstTime iso={merchant.createdAt} /></DetailRow>
          <DetailRow term="사업자">
            {/* ADMIN-UI-MERCHANT-002(D-ADMIN-UI-004 ③): 사업자 ID(biz_…)는 그리지 않는다 — 상호·마스킹 등록번호로 식별(내부 키·API·감사 불변). */}
            <span className="font-medium">{business.name}</span>
          </DetailRow>
          <DetailRow term="사업자등록번호(마스킹)"><span className="font-mono">{business.registrationNumberMasked}</span></DetailRow>
        </dl>
        {/* MERCHANT-CODE-001 MC-1b(§3.8·§5.5): 코드 이력(seq 1·2 — 바뀐 뒤 옛 코드는 "은퇴 — 다시 쓸 수 없음") + 변경 가능 여부(advisory). */}
        {(detail.merchantCodeHistory ?? []).length > 0 && (
          <section aria-label="코드 이력" className="mt-3 flex flex-col gap-1 text-sm">
            <h5 className="text-xs font-semibold text-muted-foreground">코드 이력</h5>
            <ul className="flex flex-col gap-1">
              {(detail.merchantCodeHistory ?? []).map((h) => (
                <li key={h.seq} className="flex flex-wrap items-center gap-2">
                  <Mono>{h.code}</Mono>
                  <span>{h.seq === 1 ? '첫 배정' : '변경'}</span>
                  {h.code !== merchant.merchantCode && <Badge variant="outline">은퇴 — 다시 쓸 수 없음</Badge>}
                  {h.code === merchant.merchantCode && <Badge variant="secondary">현재</Badge>}
                  <KstTime iso={h.assignedAt} />
                </li>
              ))}
            </ul>
            <p className="text-xs text-muted-foreground">
              {detail.merchantCodeChangeable === true ? '코드 변경 가능(계약 승인 전 1회)' : '코드 변경 불가(계약 승인 뒤이거나 이미 한 번 바꿨습니다)'}
            </p>
          </section>
        )}
      </Panel>

      <Panel name="Site">
        {/* LAYOUT-ROLLOUT B6(리뷰 MINOR-2): 좁은 폭은 표 가로 스크롤. 짧은 값 칸(Site ID·채널 종류·상태)만 줄바꿈 없음, 채널 식별값(최대 253자)은
            줄바꿈 허용 + 최소 폭 16ch — 한 글자씩 세로로 쪼개지지 않고, 긴 값이 표를 넓혀 상태·심사 결과 열을 밀어내지도 않게(모양만). */}
        <Table aria-label="Site 목록">
          <TableHeader>
            <TableRow><TableHead>Site</TableHead><TableHead>채널</TableHead><TableHead>상태</TableHead><TableHead>심사 결과</TableHead></TableRow>
          </TableHeader>
          <TableBody>
            {sites.length === 0 && <EmptyRow columns={4} message="등록된 Site가 없습니다." />}
            {sites.map((site) => (
              <TableRow key={site.merchantSiteId} aria-label={site.merchantSiteId}>
                <TableCell className="whitespace-nowrap"><Mono>{site.merchantSiteId}</Mono></TableCell>
                <TableCell>
                  <span className="flex flex-col">
                    <span className="whitespace-nowrap">{labelOf(site.channelType)}</span>
                    <span className="min-w-[16ch]"><Mono>{site.channelIdentifier}</Mono></span>
                  </span>
                </TableCell>
                <TableCell className="whitespace-nowrap"><StatusBadge code={site.lifecycle} /></TableCell>
                <TableCell>
                  {site.review === null ? (
                    <span className="text-muted-foreground">심사 결정 없음</span>
                  ) : (
                    <span className="flex flex-col gap-1"><StatusBadge code={site.review.decision} /><KstTime iso={site.review.decidedAt} /></span>
                  )}
                </TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
        {accounts.length > 0 && siteReview && (
          <div className="mt-4 flex flex-col gap-2">
            <h5 className="text-xs font-semibold text-muted-foreground">Site 심사</h5>
            {siteReview}
          </div>
        )}
      </Panel>

      <Panel name="계약·CSA">
        {contracts.length === 0 && <p className="text-sm text-muted-foreground">등록된 계약이 없습니다.</p>}
        {contracts.map((contract) => (
          <div key={contract.contractId} className="flex flex-col gap-2 rounded-lg border p-3">
            <p className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">계약</span><Mono>{contract.contractId}</Mono>
              <span className="text-xs text-muted-foreground">등록</span><KstTime iso={contract.createdAt} />
            </p>
            <Table aria-label={`계약 revision ${contract.contractId}`}>
              <TableHeader>
                <TableRow>
                  <TableHead>revision</TableHead><TableHead>범위</TableHead><TableHead>효력 기간</TableHead><TableHead>승인</TableHead><TableHead>등록자</TableHead>
                  {revisionAction && <TableHead><span className="sr-only">작업</span></TableHead>}
                </TableRow>
              </TableHeader>
              <TableBody>
                {contract.revisions.map((revision) => (
                  <TableRow key={revision.revisionNo} aria-label={`revision ${revision.revisionNo}`}>
                    <TableCell className="font-mono">{revision.revisionNo}</TableCell>
                    <TableCell>
                      <span className="flex flex-col gap-0.5">
                        <span>{labelOf(revision.scopeKind)}</span>
                        {revision.scopeSiteIds.map((siteId) => <Mono key={siteId}>{siteId}</Mono>)}
                      </span>
                    </TableCell>
                    <TableCell>{period(revision.effectiveFrom, revision.effectiveTo)}</TableCell>
                    <TableCell>{revision.approvedAt === null ? <span className="text-muted-foreground">{REVISION_AWAITING_APPROVAL}</span> : <KstTime iso={revision.approvedAt} />}</TableCell>
                    <TableCell><Mono>{revision.registeredBy}</Mono></TableCell>
                    {revisionAction && <TableCell>{revisionAction(contract.contractId, revision)}</TableCell>}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            <ul aria-label={`CSA ${contract.contractId}`} className="flex flex-col gap-1 text-sm">
              {contract.agreements.map((agreement) => (
                <li key={agreement.agreementId} className="flex flex-wrap items-center gap-2">
                  <span className="text-xs text-muted-foreground">CSA</span><Mono>{agreement.agreementId}</Mono>
                  <span>{labelOf(agreement.serviceKind)}</span><KstTime iso={agreement.addedAt} />
                </li>
              ))}
            </ul>
            {contractAction?.(contract.contractId)}
          </div>
        ))}
      </Panel>

      <Panel name="MID·개통">
        {accounts.length === 0 && <p className="text-sm text-muted-foreground">발급된 MID가 없습니다.</p>}
        {accounts.map((account) => (
          <div key={account.merchantAccountId} className="flex flex-col gap-3 rounded-lg border p-3">
            <p className="flex flex-wrap items-center gap-2 text-sm">
              <span className="font-medium">MID</span><Mono>{account.merchantAccountId}</Mono>
              {account.isDefault && <Badge variant="secondary">기본 MID</Badge>}
              <StatusBadge code={account.lifecycle} />
            </p>
            <p className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
              <span>발급 근거: 계약</span><Mono>{account.issuingContractId}</Mono>
              <span>· CSA</span><Mono>{account.issuingAgreementId}</Mono>
              <span>{`· revision ${account.issuingRevisionNo}`}</span>
              <span>· 발급</span><KstTime iso={account.issuedAt} />
            </p>
            <Table aria-label="Site 배정">
              <TableHeader>
                <TableRow><TableHead>Site</TableHead><TableHead>역할</TableHead><TableHead>상태</TableHead><TableHead>효력 기간</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.assignments.length === 0 && <EmptyRow columns={4} message="Site 배정이 없습니다." />}
                {account.assignments.map((assignment) => (
                  <TableRow key={assignment.assignmentId}>
                    <TableCell><Mono>{assignment.merchantSiteId}</Mono></TableCell>
                    <TableCell>{labelOf(assignment.role)}</TableCell>
                    <TableCell><StatusBadge code={assignment.status} /></TableCell>
                    <TableCell>{period(assignment.effectiveFrom, assignment.effectiveTo)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
            {activation ? activation(account) : (
              <Table aria-label="결제수단 개통">
                <TableHeader>
                  <TableRow><TableHead>결제수단</TableHead><TableHead>상태</TableHead><TableHead>등록</TableHead></TableRow>
                </TableHeader>
                <TableBody>
                  {account.paymentMethods.length === 0 && <EmptyRow columns={3} message="결제수단 개통이 없습니다." />}
                  {account.paymentMethods.map((method) => (
                    <TableRow key={method.activationId}>
                      <TableCell>{labelOf(method.paymentMethod)}</TableCell>
                      <TableCell>{method.status === null ? <span className="text-muted-foreground">상태 없음</span> : <StatusBadge code={method.status} />}</TableCell>
                      <TableCell><KstTime iso={method.registeredAt} /></TableCell>
                    </TableRow>
                  ))}
                </TableBody>
              </Table>
            )}
            {/* MERCHANT-IA-001 B1(명세 rev 0.2 §3.6): 여기 있던 통화 표(효력 기간·종료 처리)는 개통 영역 통화 표와 같은 행이라 지웠다 — '종료 처리' 열은 그 표로 옮겼다. */}
            {/* 카드 Route는 인수사 코드·Route 참조·상태만 그린다(기관 가맹점 번호는 감사 전용 — D-MS2-005). */}
            <Table aria-label="카드 Route">
              <TableHeader>
                <TableRow><TableHead>인수사 코드</TableHead><TableHead>Route 참조</TableHead><TableHead>상태</TableHead></TableRow>
              </TableHeader>
              <TableBody>
                {account.cardRoutes.length === 0 && <EmptyRow columns={3} message="카드 Route 개통이 없습니다." />}
                {account.cardRoutes.map((route) => (
                  <TableRow key={route.activationId}>
                    <TableCell className="font-mono">{route.acquirerCode}</TableCell>
                    <TableCell className="font-mono">{route.routeRef}</TableCell>
                    <TableCell><StatusBadge code={route.status} /></TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        ))}
      </Panel>

      <Panel name="한도">
        <p className="text-xs text-muted-foreground">한도는 MASTER 설정값이며 금융 사실이 아닙니다(Demo 환경의 값은 운영값이 아님).</p>
        <Table aria-label="한도 목록">
          <TableHeader>
            <TableRow>
              <TableHead>범위</TableHead><TableHead>통화</TableHead><TableHead>건당 한도</TableHead><TableHead>일 한도</TableHead><TableHead>효력 기간</TableHead><TableHead>종료 처리</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {limits.length === 0 && <EmptyRow columns={6} message="등록된 한도가 없습니다." />}
            {limits.map((limit) => (
              <TableRow key={limit.policyId} aria-label={limit.policyId}>
                <TableCell>
                  <span className="flex flex-col gap-0.5">
                    <span>{labelOf(limit.scopeKind)}</span>
                    <Mono>{limit.merchantAccountId ?? limit.agreementId ?? '-'}</Mono>
                  </span>
                </TableCell>
                <TableCell className="font-mono">{limit.currencyCode}</TableCell>
                <TableCell>{limitText(limit.perTxKind, limit.perTxAmount, limit.currencyCode)}</TableCell>
                <TableCell>{limitText(limit.dailyKind, limit.dailyAmount, limit.currencyCode)}</TableCell>
                <TableCell>{period(limit.effectiveFrom, limit.effectiveTo)}</TableCell>
                <TableCell>{limit.endedAt === null ? <span className="text-muted-foreground">-</span> : <KstTime iso={limit.endedAt} />}</TableCell>
              </TableRow>
            ))}
          </TableBody>
        </Table>
      </Panel>

      {/* D-ADMIN-UI-009 ②(사용자 결정 2026-11-24 "기술 정보 접기"): 권리 revision·권리 전파(발행·결제 서비스 수신·효력)는 기본 화면에서 빼고 기본 접힘 "기술 정보"로 옮긴다
          (값·문구·기록 없음 안내 그대로, 삭제 아님). 자리는 권리 전파 패널이 있던 상세 패널 끝(변경 이력 위) — 변경 이력은 "이력 더 보기"로 길어지므로 그 아래(맨 아래)에 두면
          찾기 어렵다. 접기는 native details(CSP — 스크립트·style 속성 없음), summary는 계정 관리 행 작업과 같은 .btn 관례(초점선 summary:focus-visible — workspace.css). */}
      <details className="group" aria-label="기술 정보">
        <summary className="btn inline-flex cursor-pointer list-none items-center gap-1.5 [&::-webkit-details-marker]:hidden">
          기술 정보
          <ChevronDownIcon aria-hidden="true" className="size-4 transition-transform group-open:rotate-180" />
        </summary>
        <div className="mt-4 flex flex-col gap-4">
          <Panel name="가맹점 권리">
            <dl>
              <DetailRow term="권리 revision"><span className="font-mono">{merchant.entitlementRevision}</span></DetailRow>
            </dl>
          </Panel>
          <Panel name="권리 전파 상태">
            {propagation === null ? (
              <p className="text-sm text-muted-foreground">권리 전파 기록이 없습니다.</p>
            ) : (
              <dl>
                <DetailRow term="발행">
                  <span className="flex flex-col"><span>{`최신 revision ${propagation.latestRevision}`}</span><KstTime iso={propagation.latestRevisionIssuedAt} /></span>
                </DetailRow>
                <DetailRow term="결제 서비스 수신">
                  <span className="flex flex-col">
                    <span>{`수신 확인 revision ${propagation.latestAckRevision ?? '-'}`}</span>
                    {propagation.latestAckAt !== null && <KstTime iso={propagation.latestAckAt} />}
                  </span>
                </DetailRow>
                <DetailRow term="효력">
                  {propagation.effectConfirmedAt === null ? (
                    <span className="text-muted-foreground">효력 확인 전</span>
                  ) : (
                    <span className="flex flex-col">
                      <KstTime iso={propagation.effectConfirmedAt} />
                      {propagation.effectBasis !== null && <span className="text-xs">{`근거: ${labelOf(propagation.effectBasis)}`}</span>}
                    </span>
                  )}
                </DetailRow>
              </dl>
            )}
          </Panel>
        </div>
      </details>
    </>
  )
}

/**
 * 가맹점 조회(MERCHANT-OPS-001 §4.1·§7 AC 11 — 읽기 전용). MERCHANT-CODE-001 MC-2: 가맹점은 **가맹점 코드**로 보이고 찾는다(목록·상세·이력 대상의 mer_… 표시 → 코드,
 * "가맹점 ID" 검색란 → "가맹점 코드" 정확 일치 검색 — 단일 기준, 은퇴 코드 표시는 MC-1b 뒤). 내부 키는 상세·명령 호출에만 쓴다. 검색(가맹점 코드·가맹점명 — 상태 조건은 MERCHANT-IA-001 B2에서 화면에서 뺌, 허용 역할에는 사업자등록번호 — ADMIN-UI-MERCHANT-002: 사업자 ID 검색란·표시 제거, 하이픈 자동) → 목록(keyset "다음") →
 * 행 "상세"로 상세 패널과 변경 이력(“이력 더 보기”). 사업자등록번호는 POST 본문으로만 보내고, 보낸 즉시 입력란을 비우며 URL·저장소·로그·화면에 남기지 않는다.
 * 화면은 마스킹 값만 보인다. 권한·감사·속도 제한은 서버가 판정한다(화면은 표시만).
 */
export function MerchantsScreen({
  api, canSearchRegistrationNumber, onSessionEnded, openMerchantId, onOpenedMerchant, onRegister, canRegisterRevision = false, revisionMemory: injectedRevisionMemory, newSubmissionId, todayKst,
  canRequestApproval = false, onOpenApprovals, memory: injectedMemory, now = () => new Date(),
}: Props) {
  const [ownMemory] = useState(createScreenMemory)
  const memory = injectedMemory ?? ownMemory
  const [view, setView] = useRemembered<View>(memory, VIEW_KEY, LIST)
  /** 승인 요청 폼을 연 Revision(`<contractId>#<revisionNo>` — 한 번에 하나, B2-2). */
  const [approvalFor, setApprovalFor] = useState<string | null>(null)
  /** 마지막 승인 요청 결과(Demo 개선 C-1 — 상세를 다시 읽어도 한 번 남는다, 다른 가맹점을 열면 지운다). */
  const [requestResult, setRequestResult] = useState<{ approvalId: string; what: string } | null>(null)
  /** "첫 MID 발급 요청" 폼 열림(B2-3). */
  const [midOpen, setMidOpen] = useState(false)
  const [codeOpen, setCodeOpen] = useState(false)
  const [ownRevisionMemory] = useState(createScreenMemory)
  const revisionMemory = injectedRevisionMemory ?? ownRevisionMemory
  /** 새 Revision 폼을 연 계약(B2-1 — 한 번에 하나). */
  const [revisionFor, setRevisionFor] = useState<string | null>(null)
  /** MC-2 코드 검색 입력(정확 일치 — 정규화 없음, 다른 조건과 함께 쓰지 않는다). */
  const [merchantCode, setMerchantCode] = useRemembered(memory, 'lookup.filter.merchantCode', '')
  const [codeError, setCodeError] = useState<'format' | 'combined' | null>(null)
  /** MERCHANT-NAME-SEARCH-001 MNS-3 가맹점명 앞부분 일치 입력(원문 그대로 보낸다 — 형식 위반은 서버 400, 사업자 조회 화면과 같은 방식). */
  const [displayNamePrefix, setDisplayNamePrefix] = useRemembered(memory, 'lookup.filter.displayNamePrefix', '')
  // MERCHANT-IA-001 B2(D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다"): 상태 필터(전체/활성/해지)를 뺐다 — 해지 경로가 없어 늘 '활성'. 예전 화면 메모리의 'lookup.filter.lifecycle' 값은 읽지 않는다(검색에 상태 조건을 보내지 않음).
  // 사업자등록번호는 화면 자체 상태 — 보낸 즉시 비운다(메모리·저장소·URL 없음).
  const [registrationNumber, setRegistrationNumber] = useState('')
  const [registrationNumberError, setRegistrationNumberError] = useState(false)
  const [sent, setSent] = useRemembered<SentSearch | null>(memory, 'lookup.sent', null)
  const [page, setPage] = useRemembered<ListPage | null>(memory, 'lookup.page', null)
  const [listError, setListError] = useState<ConsoleErrorCode | null>(null)
  const [listBusy, setListBusy] = useState(false)

  const [detail, setDetail] = useRemembered<MerchantDetail | null>(memory, 'lookup.detail', null)
  const [detailError, setDetailError] = useState<ConsoleErrorCode | null>(null)
  const [detailBusy, setDetailBusy] = useState(false)
  const [history, setHistory] = useRemembered<HistoryPage | null>(memory, HISTORY_KEY, null)
  const [historyError, setHistoryError] = useState<ConsoleErrorCode | null>(null)
  const [historyBusy, setHistoryBusy] = useState(false)
  // 다른 행을 연 뒤 늦게 도착한 앞 행 응답은 버린다(순번은 화면 메모리 범위 — [requestsOf]).
  const { list: listRequest, open: openRequest } = requestsOf(memory)

  /** 세션 종료면 로그인으로, 아니면 고정 문구 code. */
  const failure = (e: unknown): ConsoleErrorCode | null => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code) && onSessionEnded) {
      onSessionEnded(code)
      return null
    }
    return code
  }

  // 리뷰 MINOR-4: 목록 요청 순번(listRequest) — 늦게 도착한 앞 요청의 응답·오류가 새 요청 결과를 덮지 않는다(버튼도 요청 중에는 disabled).
  const runSearch = async (input: MerchantSearchInput, next: SentSearch) => {
    const request = ++listRequest.current
    setListError(null)
    setListBusy(true)
    try {
      const result = await api.merchantSearch(input)
      if (listRequest.current !== request) return
      setSent(next)
      setPage(result)
    } catch (e) {
      if (listRequest.current === request) setListError(failure(e))
    } finally {
      if (listRequest.current === request) setListBusy(false)
    }
  }

  const search = async (event?: FormEvent) => {
    event?.preventDefault()
    const brn = canSearchRegistrationNumber ? registrationNumber : ''
    if (brn !== '' && !isRegistrationNumber(brn)) {
      setRegistrationNumberError(true)
      return
    }
    setRegistrationNumberError(false)
    // MERCHANT-CODE-001 MC-2 코드 검색: §3.1 형식(고치지 않는다)·단일 기준(서버 규칙과 같이 다른 조건과 함께 보내지 않는다 — 은퇴 코드 일치 의미를 섞지 않음).
    if (merchantCode !== '') {
      if (!MERCHANT_CODE.test(merchantCode)) {
        setCodeError('format')
        return
      }
      if (displayNamePrefix !== '' || brn !== '') {
        setCodeError('combined')
        return
      }
    }
    setCodeError(null)
    const criteria: SentSearch['criteria'] = {}
    if (merchantCode !== '') criteria.merchantCode = merchantCode
    // trim·정규화 없음(MERCHANT-NAME-SEARCH-001 §2 — 앞뒤 공백 등 형식 위반은 서버 400을 기존 고정 문구로).
    if (displayNamePrefix !== '') criteria.displayNamePrefix = displayNamePrefix
    // 번호는 이 요청 본문에만 — 보내는 즉시 입력란을 비운다(실패해도 다시 입력해야 한다).
    setRegistrationNumber('')
    setPage(null)
    setDetail(null)
    setDetailError(null)
    setHistory(null)
    openRequest.current += 1
    await runSearch(brn === '' ? criteria : { ...criteria, registrationNumber: brn }, { criteria, byRegistrationNumber: brn !== '' })
  }

  const nextPage = async () => {
    if (sent === null || !page?.nextCursor) return
    if (sent.byRegistrationNumber) {
      // ADMIN-UI-MERCHANT-002: 번호는 다시 보내지 않는다(화면 상태에 번호 없음). 정확 일치 결과는 한 사업자뿐이므로 결과에 이미 있던 내부 사업자 키로 이어 찾는다
      // (사업자 ID를 화면에 보이지 않게 되어 "사업자 ID로 다시 검색" 안내를 대신한다 — 기존 businessId 조건, 새 API 없음).
      const businessId = page.items[0]?.businessId
      if (businessId === undefined) return
      const next: SentSearch = { criteria: { ...sent.criteria, businessId }, byRegistrationNumber: false }
      await runSearch({ ...next.criteria, after: page.nextCursor }, next)
      return
    }
    await runSearch({ ...sent.criteria, after: page.nextCursor }, sent)
  }

  const loadHistory = async (id: string, after: number | undefined, request: number) => {
    setHistoryError(null)
    setHistoryBusy(true)
    try {
      const result = await api.merchantHistory(id, after)
      if (openRequest.current !== request) return
      const previous = (memory.read(HISTORY_KEY) as HistoryPage | null | undefined) ?? null
      setHistory({ items: after === undefined || previous === null ? result.items : [...previous.items, ...result.items], nextCursor: result.nextCursor })
    } catch (e) {
      if (openRequest.current === request) setHistoryError(failure(e))
    } finally {
      if (openRequest.current === request) setHistoryBusy(false)
    }
  }

  /** Revision 등록 성공 뒤 같은 가맹점 상세를 다시 읽는다(화면은 유지 — 다른 가맹점을 열었으면 무시). 상세 조회 감사는 서버가 그대로 남긴다. */
  const refreshDetail = async (id: string) => {
    const request = openRequest.current
    try {
      const found = await api.merchantDetail(id)
      if (openRequest.current === request) setDetail(found)
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    }
  }

  const open = async (id: string) => {
    setView({ kind: 'detail', merchantId: id })
    setRevisionFor(null)
    setApprovalFor(null)
    setRequestResult(null)
    setMidOpen(false)
    const request = ++openRequest.current
    setDetail(null)
    setDetailError(null)
    setHistory(null)
    setHistoryError(null)
    setDetailBusy(true)
    try {
      const found = await api.merchantDetail(id)
      if (openRequest.current !== request) return
      setDetail(found)
      setDetailBusy(false)
      // 상세를 받은 뒤에만 이력을 읽는다(상세 실패 — 예: 감사 불가 — 면 이력도 보이지 않는다).
      await loadHistory(id, undefined, request)
    } catch (e) {
      if (openRequest.current === request) setDetailError(failure(e))
    } finally {
      if (openRequest.current === request) setDetailBusy(false)
    }
  }

  // 요청받은 가맹점 상세를 한 번만 연다(StrictMode 이중 effect에도 상세 조회·감사는 한 번).
  const openedInitial = useRef(false)
  useEffect(() => {
    if (!openMerchantId || openedInitial.current) return
    openedInitial.current = true
    onOpenedMerchant?.()
    void open(openMerchantId)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 마운트 때 받은 요청 한 번만
  }, [openMerchantId])

  // MERCHANT-DETAIL-VIEW-001: 기억한 화면이 상세인데 그 상세 문서가 메모리에 없으면(앞 화면에서 읽기 실패·도착 전 이동) 다시 읽는다 — 사업자 조회와 같은 규칙.
  // 문서가 있으면 다시 읽지 않는다(상세 조회 감사는 실제로 읽을 때만). 마운트 때 한 번(StrictMode 이중 effect에도 한 번), 다른 화면의 "가맹점 조회에서 보기"가 우선.
  // 리뷰 NIT-1: 문서는 있는데 이력이 없으면(이력을 읽는 중 떠났고 그 요청이 실패 — 오류는 사라진 화면 상태라 빈 패널) 이력만 다시 읽는다(상세 재조회 없음).
  const restored = useRef(false)
  useEffect(() => {
    if (restored.current) return
    restored.current = true
    if (openMerchantId) return
    if (view.kind !== 'detail') return
    if (detail?.merchant.merchantId !== view.merchantId) void open(view.merchantId)
    else if (history === null) void loadHistory(view.merchantId, undefined, openRequest.current)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- 마운트 때 한 번만
  }, [])

  // 리뷰 MINOR-2: 화면 안 버튼 전환('상세' → 상세 화면, '← 목록으로' → 목록 화면) 뒤에는 새 화면 제목(MapNote h2, tabIndex -1)으로 초점을 옮긴다 — App의 버튼 전환
  // (reviewFix1 MINOR-3)과 같은 관례·같은 초점 링(h2[tabindex="-1"]:focus-visible). 전환을 일으킨 버튼만 요청하고, 처음 열기·탭 전환은 옮기지 않는다.
  const titleRef = useRef<HTMLHeadingElement>(null)
  const focusTitle = useRef(false)
  useEffect(() => {
    if (focusTitle.current && titleRef.current) {
      focusTitle.current = false
      titleRef.current.focus()
    }
  })

  /** 상세 화면 '← 목록으로': 목록 화면으로(검색 조건·결과·다음 쪽 커서 그대로). 결과 불명 잠금·입력은 revisionMemory에 그대로 남아 같은 가맹점을 다시 열면 이어진다. */
  const backToList = () => {
    setRevisionFor(null)
    setApprovalFor(null)
    setMidOpen(false)
    setCodeOpen(false)
    setView(LIST)
    focusTitle.current = true
  }

  const moreHistory = async () => {
    if (detail === null || history?.nextCursor == null) return
    await loadHistory(detail.merchant.merchantId, history.nextCursor, openRequest.current)
  }

  // ADMIN-UI-WORKSPACE-001: 참고 시안 업무 화면(app.js moduleView — 카드 안 탭 → .filters → .tablewrap → .tablebottom). 탭 막대·카드는 MenuCard(flush).
  // 입력란 이름은 aria-label(시안처럼 보이는 label 없음 — placeholder로 무엇을 넣는지 보인다), 도움말·오류는 필터 아래 .cardnote에 두고 aria-describedby로 잇는다.
  const codeNoteId = useId()
  const registrationNoteId = useId()
  const nameNoteId = useId()
  const codeMessage = codeError === 'format' ? `가맹점 코드는 ${MERCHANT_CODE_HINT}입니다.` : codeError === 'combined' ? '가맹점 코드 검색은 다른 조건(가맹점명·사업자등록번호)과 함께 쓸 수 없습니다.' : null

  // ── 상세 화면(MERCHANT-DETAIL-VIEW-001 — 목록을 숨기고 상세만, 사업자 조회 상세와 같은 화면 전환). 상세 안 기능·잠금·감사 규칙은 목록 아래에 펼치던 때와 같다. ──
  const renderDetail = (detail: MerchantDetail) => (
    <>
      <div>
        <div className="eyebrow" aria-hidden="true">사업자·가맹점 / 상세</div>
        <h3>
          {'가맹점 상세 '}
          <small>{detail.merchant.merchantCode}</small>
        </h3>
      </div>
      {/* ADMIN-UI-MERCHANT-001: 서비스 개통 현황(기존 상세 응답에서만 계산 — 참고용, 아래 개통 점검표 유지). */}
      <ServiceOpeningTimeline detail={detail} now={now()} />
      {requestResult && (
        <section role="status" aria-label="승인 요청 결과" className="flex flex-wrap items-center gap-2 rounded-lg border border-primary/30 bg-primary/5 p-3 text-sm">
          <span>{`승인 요청을 만들었습니다 — ${requestResult.what}`}</span>
          <Mono>{requestResult.approvalId}</Mono>
          {onOpenApprovals && <Button type="button" variant="outline" size="sm" onClick={onOpenApprovals}>승인함에서 보기</Button>}
          <Button type="button" variant="ghost" size="sm" onClick={() => setRequestResult(null)}>닫기</Button>
        </section>
      )}
      {canRequestApproval && mayRequestFirstMid(detail, now()) && (
        midOpen ? (
          <FirstMidRequest
            api={api}
            detail={detail}
            now={now()}
            onRequested={(approvalId) => {
              setRequestResult({ approvalId, what: '첫 MID 발급' })
              setMidOpen(false)
              void refreshDetail(detail.merchant.merchantId)
            }}
            onSessionEnded={onSessionEnded}
          />
        ) : (
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={() => setMidOpen(true)}>첫 MID 발급 요청</Button>
          </div>
        )
      )}
      {/* MERCHANT-CODE-001 MC-1b: "코드 변경 요청"(OPERATOR — merchantCodeChangeable일 때만, 표시만; 판정·감사는 서버·MASTER). */}
      {canRequestApproval && mayRequestCodeChange(detail) && (
        codeOpen ? (
          <div className="flex flex-col gap-2">
            <MerchantCodeChangeRequest
              api={api}
              detail={detail}
              onRequested={(approvalId) => {
                setRequestResult({ approvalId, what: '가맹점 코드 변경' })
                setCodeOpen(false)
                void refreshDetail(detail.merchant.merchantId)
              }}
              onSessionEnded={onSessionEnded}
            />
            <div className="flex justify-end">
              <Button type="button" variant="outline" size="sm" onClick={() => setCodeOpen(false)}>닫기</Button>
            </div>
          </div>
        ) : (
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={() => setCodeOpen(true)}>코드 변경 요청</Button>
          </div>
        )
      )}
      <DetailPanels
        detail={detail}
        contractAction={canRegisterRevision ? (contractId) => (
          revisionFor === contractId ? (
            <div className="flex flex-col gap-2">
              <ContractRevisionForm
                api={api}
                memory={revisionMemory}
                contractId={contractId}
                merchantId={detail.merchant.merchantId}
                // 리뷰 n-4: ACTIVE Site만 후보로 보이는 것은 안내일 뿐이다 — 범위 판정의 권위는 MASTER(validScope: 다른 가맹점·비활성 Site 거절)다.
                siteIds={detail.sites.filter((site) => site.lifecycle === 'ACTIVE').map((site) => site.merchantSiteId)}
                onRegistered={() => void refreshDetail(detail.merchant.merchantId)}
                onSessionEnded={onSessionEnded}
                newSubmissionId={newSubmissionId}
                todayKst={todayKst}
              />
              <div className="flex justify-end">
                <Button type="button" variant="outline" size="sm" onClick={() => setRevisionFor(null)}>닫기</Button>
              </div>
            </div>
          ) : (
            <RevisionButton memory={revisionMemory} contractId={contractId} onOpen={() => setRevisionFor(contractId)} />
          )
        ) : undefined}
        revisionAction={canRequestApproval ? (contractId, revision) => {
          const ended = revision.effectiveTo !== null && new Date(revision.effectiveTo).getTime() <= now().getTime()
          // C-35(B3-1): 같은 그룹(계약 승인·첫 MID 발급) 대기만 숨긴다 — 개통·Site 심사 대기는 다른 그룹.
          if (revision.approvedAt !== null || ended || pendingIn(detail, APPROVAL_GROUPS.CONTRACT)) return null
          const key = `${contractId}#${revision.revisionNo}`
          return approvalFor === key ? (
            <ContractApprovalRequest
              api={api}
              merchantId={detail.merchant.merchantId}
              contractId={contractId}
              revisionNo={revision.revisionNo}
              onRequested={(approvalId) => {
                setRequestResult({ approvalId, what: `계약 승인(Revision ${revision.revisionNo})` })
                setApprovalFor(null)
                void refreshDetail(detail.merchant.merchantId)
              }}
              onOpenApprovals={onOpenApprovals}
              onSessionEnded={onSessionEnded}
            />
          ) : (
            <Button type="button" variant="outline" size="sm" onClick={() => setApprovalFor(key)}>승인 요청</Button>
          )
        } : undefined}
        siteReview={
          <SiteReviewTable
            api={api}
            detail={detail}
            canOperate={canRequestApproval}
            now={now()}
            onChanged={() => void refreshDetail(detail.merchant.merchantId)}
            onRequested={(approvalId, what) => {
              setRequestResult({ approvalId, what })
              void refreshDetail(detail.merchant.merchantId)
            }}
            onSessionEnded={onSessionEnded}
            newSubmissionId={newSubmissionId}
            // LOCK-UNIFY-001: 심사 거절 결과 불명 잠금(activation.siteReject.<site>)은 개통 영역 안에 있던 때와 같은 App 메모리에.
            memory={revisionMemory}
          />
        }
        activation={(account) => (
          <MerchantActivationArea
            api={api}
            detail={detail}
            account={account}
            canOperate={canRequestApproval}
            now={now()}
            onChanged={() => void refreshDetail(detail.merchant.merchantId)}
            onRequested={(approvalId, what) => {
              setRequestResult({ approvalId, what })
              void refreshDetail(detail.merchant.merchantId)
            }}
            onSessionEnded={onSessionEnded}
            newSubmissionId={newSubmissionId}
            // LOCK-UNIFY-001: 1인 명령 결과 불명 잠금은 App의 가맹점 조회 메모리에(상세 닫기·탭 전환에도 유지, 세션 경계 hasUnresolvedRequest).
            memory={revisionMemory}
          />
        )}
      />
      <Panel name="변경 이력">
        {historyError && <ErrorNotice>{ERROR_MESSAGES[historyError]}</ErrorNotice>}
        {history && (
          <Table aria-label="변경 이력 목록">
            <TableHeader>
              <TableRow>
                {/* MERCHANT-IA-001 B1(명세 rev 0.2 §3.8): '연산'(영문 원문, 예: CreateBusiness) 열은 '변경'과 같은 뜻이라 뺐다 — 이력 값·API는 그대로. */}
                <TableHead>순번</TableHead><TableHead>시각</TableHead><TableHead>대상</TableHead><TableHead>변경</TableHead><TableHead>행위자</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {history.items.length === 0 && <EmptyRow columns={5} message="변경 이력이 없습니다." />}
              {history.items.map((entry) => (
                <TableRow key={entry.seq} aria-label={String(entry.seq)}>
                  <TableCell className="font-mono">{entry.seq}</TableCell>
                  <TableCell><KstTime iso={entry.occurredAt} /></TableCell>
                  {/* ADMIN-HISTORY-KEYS-001(MC-2 확장): 가맹점 = 상호·코드, 사업자 = 상호·마스킹 번호 — 내부 키 mer_·biz_ 미표시. 다른 대상(Site·계약·MID 등)은 각 식별자 그대로. */}
                  <TableCell>
                    <span className="flex flex-col gap-1">
                      <KindLabel code={entry.aggregateType} label={historyTargetLabel(entry.aggregateType)} />
                      <span className="text-sm">{historyTarget(entry, detail)}</span>
                    </span>
                  </TableCell>
                  {/* MERCHANT-IA-001 B1: 한국어 이름만(영문 코드 줄 없음). 모르는 종류는 코드 그대로 — changeTypeLabel. */}
                  <TableCell>{changeTypeLabel(entry.changeType)}</TableCell>
                  <TableCell><Mono>{entry.actorRef}</Mono></TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
        {historyBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />이력을 불러오는 중입니다.</p>}
        {history?.nextCursor != null && (
          <div className="flex justify-end">
            <Button type="button" variant="outline" size="sm" onClick={moreHistory} aria-busy={historyBusy}>이력 더 보기</Button>
          </div>
        )}
      </Panel>
    </>
  )
  if (view.kind === 'detail') {
    const current = detail !== null && detail.merchant.merchantId === view.merchantId ? detail : null
    return (
      <>
        {/* MERCHANT-IA-001 B1(명세 rev 0.2 §3.2): "조회 전용"은 사실과 달라(상세에 요청·등록 버튼이 있다) 뺐다. 감사 안내는 그대로. */}
        <MapNote title="가맹점 조회" titleRef={titleRef} action={<BackToListButton onClick={backToList} />}>
          상세 조회는 감사 기록에 남습니다.
        </MapNote>
        <div className="detailarea">
          {detailBusy && <p className="flex items-center gap-2 text-sm text-muted-foreground"><Spinner active />상세를 불러오는 중입니다.</p>}
          {detailError && <ErrorNotice>{ERROR_MESSAGES[detailError]}</ErrorNotice>}
          {current && renderDetail(current)}
        </div>
      </>
    )
  }

  // ── 목록 화면 ──
  return (
    <>
      <MapNote title="가맹점 조회" titleRef={titleRef} action={onRegister && <button type="button" className="btn primary" onClick={onRegister}>＋ 가맹점 등록</button>}>
        {/* MERCHANT-IA-001 B1(명세 rev 0.2 §3.1): 목록에는 개통 상태가 없고 '＋ 가맹점 등록'·상세 요청이 있어 "개통 상태를 조회"·"조회 전용"은 사실과 달라 뺐다. 감사 안내는 그대로. */}
        상세 조회는 감사 기록에 남습니다.
      </MapNote>
      <form className="filters" onSubmit={search}>
        <input
          aria-label="가맹점 코드"
          value={merchantCode}
          onChange={(e) => setMerchantCode(e.target.value)}
          autoComplete="off"
          spellCheck={false}
          placeholder="가맹점 코드 (예: demoshop01)"
          aria-describedby={codeNoteId}
          aria-invalid={codeError !== null || undefined}
        />
        <input
          aria-label="가맹점명"
          value={displayNamePrefix}
          onChange={(e) => setDisplayNamePrefix(e.target.value)}
          autoComplete="off"
          spellCheck={false}
          placeholder="가맹점명 (앞부분 일치)"
          aria-describedby={codeError === 'combined' ? `${nameNoteId} ${codeNoteId}` : nameNoteId}
        />
        {canSearchRegistrationNumber && (
          <RegistrationNumberInput
            plain
            aria-label="사업자등록번호"
            placeholder="사업자등록번호 000-00-00000"
            value={registrationNumber}
            onValueChange={setRegistrationNumber}
            aria-describedby={registrationNoteId}
            aria-invalid={registrationNumberError || undefined}
          />
        )}
        <button type="submit" className="btn primary" disabled={listBusy} aria-busy={listBusy}>
          검색
        </button>
      </form>
      <div className="cardnote">
        <p id={codeNoteId} className={codeMessage ? 'error' : 'subtle'}>{codeMessage ?? '가맹점 코드: 정확히 일치하는 코드 하나(예전에 쓰던 코드도 찾습니다).'}</p>
        <p id={nameNoteId} className="subtle">가맹점명: 앞부분이 같은 이름(영문 대소문자 구분, 앞뒤 공백 없이)</p>
        {canSearchRegistrationNumber && (
          <p id={registrationNoteId} className={registrationNumberError ? 'error' : 'subtle'}>
            {registrationNumberError ? '사업자등록번호는 숫자 10자리입니다.' : '사업자등록번호: 검색은 매번 감사 기록에 남습니다(정확히 일치하는 번호만).'}
          </p>
        )}
        {listError && <ErrorNotice>{ERROR_MESSAGES[listError]}</ErrorNotice>}
      </div>

      {page && (
        <>
          <div className="tablewrap">
            <table aria-label="가맹점 목록">
              <thead>
                <tr>
                  {/* MERCHANT-IA-001 B2(D-ADMIN-UI-010 ② "늘 같은 값은 화면에서 뺀다"): 상태 열(늘 '활성')을 뺐다. */}
                  <th>가맹점</th><th>사업자</th><th>사업자등록번호(마스킹)</th>
                  <th>MID 수</th><th>등록일</th><th><span className="sr-only">작업</span></th>
                </tr>
              </thead>
              <tbody>
                {page.items.map((row) => (
                  <tr key={row.merchantId} aria-label={row.merchantCode} className={detail?.merchant.merchantId === row.merchantId ? 'selected' : undefined}>
                    <td>
                      <strong>{row.merchantName}</strong>
                      <small>{row.merchantCode}</small>
                    </td>
                    <td>{row.businessName}</td>
                    <td>{row.registrationNumberMasked}</td>
                    <td data-col="midCount">{row.midCount}</td>
                    <td><KstTime iso={row.createdAt} /></td>
                    <td>
                      <button type="button" className="btn" onClick={() => { void open(row.merchantId); focusTitle.current = true }}>상세</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {page.items.length === 0 && <div className="empty">조건에 맞는 가맹점이 없습니다.</div>}
          </div>
          <div className="tablebottom">
            <span>{`이 쪽 ${page.items.length}건`}</span>
            {page.nextCursor !== null && (
              <button type="button" className="btn" onClick={nextPage} disabled={listBusy} aria-busy={listBusy}>다음</button>
            )}
          </div>
        </>
      )}

    </>
  )
}
