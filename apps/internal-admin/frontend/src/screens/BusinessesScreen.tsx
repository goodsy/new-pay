import { useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, BusinessDetail, BusinessHistoryChange, BusinessHistoryEntry, BusinessListItem, BusinessLookupItem, BusinessSearchInput } from '../api/adminApi'
import { EmptyRow, Field, KstTime, Spinner } from '../components/DataBits'
// 상세 카드·줄은 components/DetailCard.tsx로 그대로 옮김(출력 동일 — 등록 폼 FormRow와 나란히 쓰는 공용 구성요소).
import { DetailRow as Row, InfoCard } from '../components/DetailCard'
import { DetailTabs } from '../components/DetailTabs'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { SearchIcon } from '../components/icons'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Input } from '../components/ui/input'
import { NativeSelect } from '../components/ui/native-select'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import {
  ADDRESS_TYPE_LABELS, BRN_RATE_LIMITED, BUSINESS_READ_RATE_LIMITED, BUSINESS_TYPE_LABELS, CATEGORY_TYPE_LABELS, CHANGE_TYPE_LABELS, formatCorporateNumber, formatPhone,
  HISTORY_FIELD_LABELS, labelIn, REGISTRATION_STATUS_LABELS, REPRESENTATIVE_TYPE_LABELS, representativeSummary,
} from '../lib/businessProfile'
import { defaultSubmissionId, type BusinessRef, type HandoffState } from '../lib/registration'
import { displayRegistrationNumber, isRegistrationNumber } from '../lib/registrationNumber'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'

/**
 * 사업자 조회 탭(BUSINESS-PROFILE-001 §8.2 — BIZ-4): 목록·검색(keyset "더 보기") → 상세(섹션 1~6 원문 — D-BIZ-006 ②, 열람마다 서버 감사) → 이력·수정·등록 완료.
 * - 조회 역할 OPERATOR·APPROVER(D-BIZ-006 ① — 표시만, 판정은 서버). 명령 버튼(수정·이어서 입력·이 사업자로 가맹점 등록)은 [canEdit](OPERATOR)일 때만.
 * - 번호 검색은 번호 조회 API만(C-6 — 목록 본문에 번호 없음, 입력 시 다른 조건 비활성, 보낸 즉시 비움). 목록 본문은 빈 조건의 키를 생략한다(null 없음 — BIZ-3 리뷰 1).
 * - 선택한 사업자 키(biz_)는 App 메모리에만([openBusinessIn]) — URL·location·브라우저 저장소·DOM에 없다(X-4·D-ADMIN-UI-004 ③). Business ID 열 없음.
 * - 사업자등록번호는 원문을 하이픈 표시(BRN-UNMASK-001 — D-BIZ-007·D-PII-001 ①: 목록·번호 조회 결과·상세, [displayRegistrationNumber]). URL·저장소에는 없다. 진위확인(늘 "미검증" — TC-21)·국가(늘 KR)는 화면에 그리지 않는다(MERCHANT-IA-001 B2, D-ADMIN-UI-010 ② — 값·API 그대로). 429는 공유 조회 창·번호 버킷 문구(Demo 값 — L-14).
 * - 저장 성공 뒤 상세를 다시 읽는다(C-18). DRAFT 상세는 "새 가맹점 등록 불가" 안내(L-7·D-BIZ-001).
 */
interface Props {
  api: AdminApi
  /** 화면 상태(App 메모리 — 탭 전환에도 유지, 세션 경계마다 새로). 선택한 사업자 키도 여기에만. */
  memory: ScreenMemory
  /** OPERATOR(등록·수정·등록 완료 — D-BIZ-006 ①). 표시만 — 판정은 서버. */
  canEdit: boolean
  /** "이 사업자로 가맹점 등록"(REGISTERED만 — D-BIZ-001). [handoffState]가 locked가 아닐 때만 부른다. */
  onContinue: (business: BusinessRef) => void
  /** 목록 머리 '＋ 사업자 등록'(D-ADMIN-UI-006 — 같은 탭 안 등록 화면). 호출자가 이 역할에게 등록 화면이 보일 때만(OPERATOR — pagesFor) 준다. 없으면 버튼 없음. */
  onRegister?: () => void
  handoffState?: () => HandoffState
  onSessionEnded?: (code: ConsoleErrorCode) => void
  newSubmissionId?: () => string
}

type View = { kind: 'list' } | { kind: 'detail'; businessId: string } | { kind: 'edit'; businessId: string }
interface Filters { displayNamePrefix: string; businessType: string; registrationStatus: string; createdFrom: string; createdTo: string }
/** 목록 결과(검색 = keyset 쪽 이어붙임, 번호 조회 = 0/1건 — 같은 표 모양). */
/** 목록 결과 — 일반 검색(BusinessListItem)과 번호 조회(BusinessLookupItem, 짧은 결과 모양 — MERCHANT-IA-001 B2)는 행 모양이 달라 출처로 나눈다. */
type ListState =
  | { source: 'search'; criteria: BusinessSearchInput; items: BusinessListItem[]; next: string | null }
  | { source: 'lookup'; criteria: BusinessSearchInput; items: BusinessLookupItem[]; next: null }
interface HistoryState { businessId: string; items: BusinessHistoryEntry[]; nextAfter: number | null }

const LIST: View = Object.freeze({ kind: 'list' })
/** 일반 검색 결과 열(MERCHANT-IA-001 B2 — 진위확인 열은 늘 '미검증'이라 뺐다, D-ADMIN-UI-010 ②). */
const SEARCH_COLUMNS = ['사업자등록번호', '상호', '대표자', '사업체 구분', '등록 상태', '가맹점 수', '수정일', '보기'] as const
/** 사업자등록번호 조회 결과 열(D-ADMIN-UI-010 ⑦ 짧은 결과 모양 — 번호 조회 응답에 있는 값만). */
const LOOKUP_COLUMNS = ['사업자등록번호', '상호', '등록 상태', '상세'] as const
const NO_FILTERS: Filters = Object.freeze({ displayNamePrefix: '', businessType: '', registrationStatus: '', createdFrom: '', createdTo: '' })
const EDIT_KEY = 'edit'
/** 편집 기준 상세(리뷰 MINOR-1): 수정을 연 시점의 문서 — 폼의 이전 값·보내는 version은 항상 이것(지금 상세 version이 아님 — 낙관적 잠금 우회 방지). */
const EDIT_BASE = 'edit.base'

/** 결과 확인이 필요한 편집(보내는 중·결과 불명)이 있으면 그 사업자 키. 세션 안에서 버리지 않는다(같은 submissionId 재전송·hasUnresolvedRequest 유지). */
export function unresolvedEditOf(memory: ScreenMemory): string | null {
  const status = memory.read(`${EDIT_KEY}.cmd.status`) as { kind?: string } | undefined
  const base = memory.read(EDIT_BASE) as BusinessDetail | undefined
  return (status?.kind === 'busy' || status?.kind === 'unknown') && base ? base.business.businessId : null
}

const clearEdit = (memory: ScreenMemory) => {
  for (const k of memory.keys()) if (k.startsWith(`${EDIT_KEY}.`)) memory.remove(k)
}

/** 다른 화면의 상세 링크(중복 안내 — D-BIZ-003, 가맹점 등록 ① DRAFT — D-BIZ-006 ③): 메모리에 선택만 두고 상세를 다시 읽게 한다(URL 없음). */
export function openBusinessIn(memory: ScreenMemory, businessId: string) {
  memory.write('view', { kind: 'detail', businessId })
  memory.write('detail.tab', 'info')
  memory.remove('detail.doc')
  memory.remove('history')
}

/**
 * 사업자 조회 탭을 목록으로(LAYOUT-ROLLOUT B1 reviewFix1 MINOR-1 — 등록 화면 '목록으로'·등록 화면에서 부모 탭 클릭). 기억한 화면('view')만 바꾼다 —
 * 결과 확인용 편집 메모리(edit.*·edit.base)·목록·조건은 그대로(목록의 "결과 확인이 필요한 사업자 열기"로 다시 갈 수 있다).
 */
export function showBusinessListIn(memory: ScreenMemory) {
  memory.write('view', LIST)
}

const messageOf = (code: ConsoleErrorCode, lookup = false) =>
  code === 'RATE_LIMITED' ? (lookup ? BRN_RATE_LIMITED : BUSINESS_READ_RATE_LIMITED) : ERROR_MESSAGES[code]

export function BusinessesScreen({ api, memory, canEdit, onContinue, onRegister, handoffState = () => 'empty', onSessionEnded, newSubmissionId = defaultSubmissionId }: Props) {
  const [view, setView] = useRemembered<View>(memory, 'view', LIST)
  const [filters, setFilters] = useRemembered<Filters>(memory, 'filters', NO_FILTERS)
  const [list, setList] = useRemembered<ListState | null>(memory, 'list', null)
  const [doc, setDoc] = useRemembered<BusinessDetail | null>(memory, 'detail.doc', null)
  const [tab, setTab] = useRemembered<'info' | 'history'>(memory, 'detail.tab', 'info')
  const [history, setHistory] = useRemembered<HistoryState | null>(memory, 'history', null)
  // 평문 번호는 화면 자체 상태 — 보낸 즉시 비운다(메모리·저장소·URL 없음).
  const [number, setNumber] = useState('')
  const [numberError, setNumberError] = useState(false)
  const [busy, setBusy] = useState(false)
  // 리뷰 NIT-6: 목록 오류는 메모리에 둔다 — 첫 목록 읽기가 실패한 뒤 탭에 다시 들어와도 자동으로 다시 읽지 않는다("다시 시도" 버튼만).
  const [error, setError] = useRemembered<string | null>(memory, 'list.error', null)
  const [detailError, setDetailError] = useState<string | null>(null)
  const [handoff, setHandoff] = useState<'locked' | 'confirm' | null>(null)
  const [editBlocked, setEditBlocked] = useState(false)
  const [editBase] = useRemembered<BusinessDetail | null>(memory, EDIT_BASE, null)
  const [editStatus] = useRemembered<{ kind?: string } | null>(memory, `${EDIT_KEY}.cmd.status`, null)
  const pendingEditId = (editStatus?.kind === 'busy' || editStatus?.kind === 'unknown') && editBase ? editBase.business.businessId : null

  const fail = (e: unknown, lookup = false, target: (m: string) => void = setError) => {
    const code = codeOf(e)
    if (SESSION_ENDING.includes(code)) onSessionEnded?.(code)
    else target(messageOf(code, lookup))
  }

  const runSearch = async (criteria: BusinessSearchInput, append: boolean) => {
    setBusy(true)
    setError(null)
    try {
      const page = await api.searchBusinesses(append && list?.next ? { ...criteria, after: list.next } : criteria)
      setList({ source: 'search', criteria, items: append && list?.source === 'search' ? [...list.items, ...page.items] : page.items, next: page.next })
    } catch (e) {
      fail(e)
    } finally {
      setBusy(false)
    }
  }

  // 목록 첫 쪽(조건 없음 — 본문 `{}`): 목록 화면에 처음 들어올 때 한 번.
  useEffect(() => {
    if (view.kind === 'list' && list === null && !busy && error === null) void runSearch({}, false)
  }, [view.kind, list])

  // 상세: 선택한 사업자 문서가 없으면 읽는다(열람마다 서버 감사 BUSINESS_VIEWED).
  useEffect(() => {
    if (view.kind !== 'detail' || (doc && doc.business.businessId === view.businessId)) return
    let alive = true
    setDetailError(null)
    api.businessDetail(view.businessId)
      .then((d) => alive && setDoc(d))
      .catch((e) => alive && fail(e, false, setDetailError))
    return () => {
      alive = false
    }
  }, [view, doc])

  const criteriaOf = (f: Filters): BusinessSearchInput => {
    const criteria: BusinessSearchInput = {}
    if (f.displayNamePrefix !== '') criteria.displayNamePrefix = f.displayNamePrefix
    if (f.businessType !== '') criteria.businessType = f.businessType as BusinessSearchInput['businessType']
    if (f.registrationStatus !== '') criteria.registrationStatus = f.registrationStatus as BusinessSearchInput['registrationStatus']
    if (f.createdFrom !== '') criteria.createdFrom = f.createdFrom
    if (f.createdTo !== '') criteria.createdTo = f.createdTo
    return criteria
  }

  const search = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) return
    if (number === '') {
      await runSearch(criteriaOf(filters), false)
      return
    }
    if (!isRegistrationNumber(number)) {
      setNumberError(true)
      return
    }
    setNumberError(false)
    const registrationNumber = number
    setNumber('')
    setBusy(true)
    setError(null)
    try {
      const result = await api.lookupBusinessByRegistrationNumber(registrationNumber)
      setList({ source: 'lookup', criteria: {}, items: result.items, next: null })
    } catch (e) {
      fail(e, true)
    } finally {
      setBusy(false)
    }
  }

  // MERCHANT-DETAIL-VIEW-001 리뷰 MINOR-2: 화면 안 버튼 전환(목록 '상세' → 상세, '목록으로' → 목록) 뒤 새 화면 제목(h2, tabIndex -1)으로 초점 — App 버튼 전환과 같은 관례.
  // 상세 제목(상호)은 문서를 읽은 뒤 그려지므로 요청을 남겨 두고 제목이 생긴 렌더에서 옮긴다. 처음 열기·탭 전환·다른 화면 링크는 옮기지 않는다.
  const titleRef = useRef<HTMLHeadingElement>(null)
  const focusTitle = useRef(false)
  useEffect(() => {
    if (focusTitle.current && titleRef.current) {
      focusTitle.current = false
      titleRef.current.focus()
    }
  })

  const openDetail = (businessId: string) => {
    setHandoff(null)
    setEditBlocked(false)
    openBusinessIn(memory, businessId)
  }

  const loadHistory = async (businessId: string, more: boolean) => {
    setDetailError(null)
    try {
      const page = await api.businessHistory(businessId, more ? history?.nextAfter ?? undefined : undefined)
      setHistory({ businessId, items: more && history ? [...history.items, ...page.items] : page.items, nextAfter: page.nextAfter })
    } catch (e) {
      fail(e, false, setDetailError)
    }
  }

  /**
   * 수정 시작(리뷰 MINOR-1): 결과 확인이 필요한 편집이 있으면 버리지 않는다 — 같은 사업자면 그 편집(기준 version 그대로)으로 이어서 열고, 다른 사업자면 막고 안내한다.
   * 없으면 지금 상세를 편집 기준으로 새로 고정한다.
   */
  const startEdit = (current: BusinessDetail) => {
    const pendingId = unresolvedEditOf(memory)
    if (pendingId !== null && pendingId !== current.business.businessId) {
      setEditBlocked(true)
      return
    }
    if (pendingId === null) {
      clearEdit(memory)
      memory.write(EDIT_BASE, current)
    }
    setEditBlocked(false)
    setView({ kind: 'edit', businessId: current.business.businessId })
  }

  const continueWith = (business: BusinessRef) => {
    const state = handoffState()
    if (state === 'locked') setHandoff('locked')
    else if (state === 'inProgress') setHandoff('confirm')
    else {
      setHandoff(null)
      onContinue(business)
    }
  }

  // ── 편집 ──
  if (view.kind === 'edit') {
    // 리뷰 MINOR-1: 폼은 편집 기준(edit.base)으로만 그린다 — 그 사이 상세가 다시 읽혀 version이 바뀌어도 보내는 version은 기준 값(서버가 충돌로 판정).
    const base = editBase && editBase.business.businessId === view.businessId ? editBase : null
    if (!base) {
      return <ErrorNotice>상세를 먼저 불러오세요.</ErrorNotice>
    }
    return (
      <BusinessProfileFormScreen
        key={`${base.business.businessId}:${base.business.version}`}
        api={api}
        mode={{ kind: 'edit', detail: base }}
        memory={memory}
        memoryKey={EDIT_KEY}
        newSubmissionId={newSubmissionId}
        onSessionEnded={onSessionEnded}
        onSaved={(businessId) => {
          clearEdit(memory)
          openDetail(businessId)
        }}
        onOpenBusiness={openDetail}
        onCancel={() => {
          clearEdit(memory)
          setView({ kind: 'detail', businessId: view.businessId })
        }}
        onReload={() => {
          clearEdit(memory)
          openDetail(view.businessId)
        }}
        onAbandon={() => {
          // 결과 불명 포기: 편집 메모리를 비우고 상세를 다시 읽는다(다음 수정은 최신 상세가 기준).
          clearEdit(memory)
          openDetail(view.businessId)
        }}
      />
    )
  }

  // ── 상세 ──
  if (view.kind === 'detail') {
    const current = doc && doc.business.businessId === view.businessId ? doc : null
    return (
      <section aria-label="사업자 상세" className="flex flex-col gap-4">
        <div>
          <Button type="button" variant="outline" size="sm" onClick={() => { setHandoff(null); setView(LIST); focusTitle.current = true }}>목록으로</Button>
        </div>
        {detailError && <ErrorNotice>{detailError}</ErrorNotice>}
        {!current && !detailError && <p className="text-sm text-muted-foreground"><Spinner active /> 불러오는 중…</p>}
        {current && (
          <>
            <div className="flex flex-wrap items-center gap-2">
              {/* 리뷰 MINOR-2: 상세 화면 제목 — 버튼 전환 초점 자리(h2 tabIndex -1, 같은 초점 링). 목록 제목(h2)과 같은 단계(목록이 숨겨진 화면 — 모양 class 그대로). */}
              <h2 ref={titleRef} tabIndex={-1} className="text-base font-bold text-heading">{current.business.businessName}</h2>
              <Badge variant={current.business.registrationStatus === 'REGISTERED' ? 'success' : 'warning'}>{labelIn(REGISTRATION_STATUS_LABELS, current.business.registrationStatus)}</Badge>
              {/* MERCHANT-IA-001 B2(D-ADMIN-UI-010 ②): "진위확인 미검증" 배지는 늘 같은 값이라 뺐다. 오해(등록 완료 = 검증됨)는 바로 아래 안내 문구가 막는다 — 그 문구는 지우지 않는다. */}
            </div>
            <p className="text-xs text-muted-foreground">등록 완료는 내부 기본정보 등록 완료를 뜻하며 진위확인·AML/KYB·계약 승인이 아닙니다.</p>
            {current.business.registrationStatus === 'DRAFT' && (
              <WarningNote>
                임시저장 사업자에는 새 가맹점을 등록할 수 없습니다. 필수 정보를 입력해 등록 완료하세요(이미 등록된 가맹점·계약·MID는 그대로 동작합니다).
                {current.business.businessType === null ? ' 사업체 구분이 비어 있는 기존 사업자입니다 — 처음 수정할 때 구분을 고르세요.' : ''}
              </WarningNote>
            )}
            {canEdit && (
              <div className="flex flex-wrap gap-2">
                {current.business.registrationStatus === 'DRAFT' ? (
                  <Button type="button" onClick={() => startEdit(current)}>이어서 입력·등록 완료</Button>
                ) : (
                  <>
                    <Button type="button" variant="outline" onClick={() => startEdit(current)}>수정</Button>
                    <Button type="button" onClick={() => continueWith({ businessId: current.business.businessId, name: current.business.businessName, registrationNumber: current.business.registrationNumber })}>
                      이 사업자로 가맹점 등록
                    </Button>
                  </>
                )}
              </div>
            )}
            {canEdit && pendingEditId === current.business.businessId && (
              <WarningNote>이 사업자에 결과 확인이 필요한 수정이 있습니다 — "수정"/"이어서 입력"으로 열어 "같은 내용 다시 보내기"로 먼저 확인하세요.</WarningNote>
            )}
            {canEdit && editBlocked && pendingEditId !== null && pendingEditId !== current.business.businessId && (
              <div className="flex flex-col gap-2">
                <WarningNote>다른 사업자에 결과 확인이 필요한 수정이 있어 새 수정을 시작하지 않았습니다 — 그 사업자 상세에서 먼저 확인하세요.</WarningNote>
                <div><Button type="button" variant="outline" size="sm" onClick={() => openDetail(pendingEditId)}>결과 확인이 필요한 사업자 열기</Button></div>
              </div>
            )}
            {handoff === 'locked' && <WarningNote>가맹점 등록에 결과 확인이 필요한 단계가 있습니다 — 가맹점 등록에서 '같은 내용 다시 보내기'로 먼저 확인하세요.</WarningNote>}
            {handoff === 'confirm' && (
              <div role="group" aria-label="가맹점 등록 새로 시작 확인" className="flex flex-col gap-2">
                <WarningNote>진행 중인 가맹점 등록이 있습니다. 그 내용을 지우고 이 사업자로 새로 시작할까요? (이미 저장된 가맹점·계약은 지워지지 않습니다)</WarningNote>
                <div className="flex justify-end gap-2">
                  <Button type="button" variant="outline" onClick={() => setHandoff(null)}>취소</Button>
                  <Button
                    type="button"
                    onClick={() => {
                      setHandoff(null)
                      if (handoffState() === 'locked') setHandoff('locked')
                      else onContinue({ businessId: current.business.businessId, name: current.business.businessName, registrationNumber: current.business.registrationNumber })
                    }}
                  >
                    새로 시작
                  </Button>
                </div>
              </div>
            )}
            {/* MERCHANT-IA-001 B4(ADMIN-DS-001 §5 "상세 탭" 정본): 같은 모양의 공용 DetailTabs로 — tabpanel·aria-controls·좌우/Home/End, 패널은 hidden으로 숨긴다(언마운트 없음). 이력은 탭을 처음 열 때 읽는다(그대로). */}
            <DetailTabs<'info' | 'history'>
              label="사업자 상세 보기"
              selected={tab}
              onSelect={(t) => {
                setTab(t)
                if (t === 'history' && history?.businessId !== current.business.businessId) void loadHistory(current.business.businessId, false)
              }}
              tabs={[
                { key: 'info', label: '기본정보', panel: <DetailInfo detail={current} /> },
                { key: 'history', label: '변경 이력', panel: <HistoryTable history={history?.businessId === current.business.businessId ? history : null} onMore={() => loadHistory(current.business.businessId, true)} /> },
              ]}
            />
          </>
        )}
      </section>
    )
  }

  // ── 목록 ──
  const set = (patch: Partial<Filters>) => setFilters({ ...filters, ...patch })
  const byNumber = number !== ''
  return (
    <section className="flex flex-col gap-4">
      <PageHeader
        titleRef={titleRef}
        title="사업자 조회"
        description="등록된 사업자(가맹점이 없는 사업자 포함)를 찾고 상세·변경 이력을 확인합니다. 상세 열람은 감사 기록에 남습니다."
        action={onRegister && <button type="button" className="btn primary" onClick={onRegister}>＋ 사업자 등록</button>}
        focusable
      />
      <form aria-label="사업자 검색" onSubmit={search} noValidate className="grid gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Field label="사업자등록번호" error={numberError ? '사업자등록번호는 숫자 10자리입니다.' : undefined} hint="번호로 찾으면 다른 조건은 쓰지 않습니다(조회는 감사 기록에 남습니다)." className="sm:col-span-2">
          {(describedBy) => <RegistrationNumberInput value={number} onValueChange={(v) => { setNumber(v); setNumberError(false) }} aria-describedby={describedBy} aria-invalid={numberError || undefined} />}
        </Field>
        <Field label="상호(앞부분 일치)">
          {() => <Input value={filters.displayNamePrefix} disabled={byNumber} autoComplete="off" onChange={(e) => set({ displayNamePrefix: e.target.value })} />}
        </Field>
        <Field label="사업체 구분">
          {() => (
            <NativeSelect aria-label="사업체 구분" value={filters.businessType} disabled={byNumber} onChange={(e) => set({ businessType: e.target.value })}>
              <option value="">전체</option>
              <option value="INDIVIDUAL">개인</option>
              <option value="CORPORATION">법인</option>
            </NativeSelect>
          )}
        </Field>
        <Field label="등록 상태">
          {() => (
            <NativeSelect aria-label="등록 상태" value={filters.registrationStatus} disabled={byNumber} onChange={(e) => set({ registrationStatus: e.target.value })}>
              <option value="">전체</option>
              <option value="DRAFT">임시저장</option>
              <option value="REGISTERED">등록 완료</option>
            </NativeSelect>
          )}
        </Field>
        <div className="flex flex-col gap-3 sm:col-span-3 sm:flex-row lg:col-span-6">
          <Field label="등록일 시작">
            {() => <Input type="date" value={filters.createdFrom} disabled={byNumber} onChange={(e) => set({ createdFrom: e.target.value })} />}
          </Field>
          <Field label="등록일 끝">
            {() => <Input type="date" value={filters.createdTo} disabled={byNumber} onChange={(e) => set({ createdTo: e.target.value })} />}
          </Field>
          <div className="flex items-end">
            <Button type="submit" variant="outline" aria-busy={busy}>
              {busy ? <Spinner active /> : <SearchIcon aria-hidden="true" />}
              조회
            </Button>
          </div>
        </div>
      </form>
      {error && (
        <div className="flex flex-col gap-2">
          <ErrorNotice>{error}</ErrorNotice>
          {list === null && (
            <div><Button type="button" variant="outline" size="sm" disabled={busy} onClick={() => runSearch(criteriaOf(filters), false)}>다시 시도</Button></div>
          )}
        </div>
      )}
      {list && (
        <>
          <Table aria-label="사업자 목록">
            <TableHeader>
              <TableRow>
                {(list.source === 'lookup' ? LOOKUP_COLUMNS : SEARCH_COLUMNS).map((h) => <TableHead key={h}>{h}</TableHead>)}
              </TableRow>
            </TableHeader>
            <TableBody>
              {list.items.length === 0 ? (
                <EmptyRow columns={(list.source === 'lookup' ? LOOKUP_COLUMNS : SEARCH_COLUMNS).length} message={list.source === 'lookup' ? '이 번호로 등록된 사업자가 없습니다.' : '조건에 맞는 사업자가 없습니다.'} />
              ) : (
                // MERCHANT-IA-001 B2(D-ADMIN-UI-010 ⑦): 번호 조회 결과는 짧은 결과 모양 — 응답(BusinessLookupItem)에 있는 값만, '—' 칸을 그리지 않는다.
                list.source === 'lookup' ? list.items.map((item) => (
                    <TableRow key={item.businessId}>
                      <TableCell className="font-mono">{displayRegistrationNumber(item.registrationNumber)}</TableCell>
                      <TableCell className="font-medium">{item.businessName}</TableCell>
                      <TableCell>{labelIn(REGISTRATION_STATUS_LABELS, item.registrationStatus)}</TableCell>
                      <TableCell>
                        <Button type="button" variant="outline" size="sm" aria-label={`${item.businessName} 상세`} onClick={() => { openDetail(item.businessId); focusTitle.current = true }}>상세</Button>
                      </TableCell>
                    </TableRow>
                )) : list.items.map((item) => (
                    <TableRow key={item.businessId}>
                      <TableCell className="font-mono">{displayRegistrationNumber(item.registrationNumber)}</TableCell>
                      <TableCell className="font-medium">{item.businessName}</TableCell>
                      <TableCell>{representativeSummary(item.representativeNameMasked, item.representativeCount)}</TableCell>
                      <TableCell>{labelIn(BUSINESS_TYPE_LABELS, item.businessType)}</TableCell>
                      <TableCell>{labelIn(REGISTRATION_STATUS_LABELS, item.registrationStatus)}</TableCell>
                      <TableCell>{item.merchantCount}</TableCell>
                      <TableCell><KstTime iso={item.updatedAt} /></TableCell>
                      <TableCell>
                        <Button type="button" variant="outline" size="sm" aria-label={`${item.businessName} 상세`} onClick={() => { openDetail(item.businessId); focusTitle.current = true }}>상세</Button>
                      </TableCell>
                    </TableRow>
                ))
              )}
            </TableBody>
          </Table>
          {list.next && (
            <div className="flex justify-center">
              <Button type="button" variant="outline" disabled={busy} onClick={() => runSearch(list.criteria, true)}>더 보기</Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}

/** 상세 섹션 1~6(원문 — D-BIZ-006 ②. 사업자등록번호도 원문(D-BIZ-007), 하이픈은 표시만). */
function DetailInfo({ detail }: { detail: BusinessDetail }) {
  const { business, registration } = detail
  const names = (type: string) => detail.categories.filter((c) => c.categoryType === type).map((c) => c.name)
  const none = <span className="text-muted-foreground">미입력</span>
  const or = (value: string | null | undefined, show: (v: string) => ReactNode = (v) => v) => (value ? show(value) : none)
  return (
    <div className="grid gap-4 lg:grid-cols-2">
      <InfoCard title="1. 사업자 기본">
        <Row term="사업자등록번호"><span className="font-mono">{displayRegistrationNumber(business.registrationNumber)}</span></Row>
        <Row term="상호">{business.businessName}</Row>
        <Row term="사업체 구분">{labelIn(BUSINESS_TYPE_LABELS, business.businessType)}</Row>
        {/* MERCHANT-IA-001 B2(D-ADMIN-UI-010 ②, 명세 §3.10): '등록 상태'는 머리 배지와 중복, '진위확인'(늘 미검증)·'국가'(늘 KR)는 늘 같은 값이라 뺐다(값·API 그대로). */}
        {business.businessType === 'CORPORATION' && <Row term="법인등록번호">{or(registration.corporateRegistrationNumber, (v) => <span className="font-mono">{formatCorporateNumber(v)}</span>)}</Row>}
        <Row term="개업일">{or(registration.openingDate)}</Row>
        <Row term="영문 상호">{or(registration.englishName)}</Row>
        <Row term="가맹점 수">{business.merchantCount}</Row>
        <Row term="등록일"><KstTime iso={business.createdAt} /></Row>
        <Row term="수정일"><KstTime iso={business.updatedAt} /></Row>
      </InfoCard>
      <InfoCard title="2. 사업 내용">
        <Row term="업태">{names('BUSINESS_TYPE').length > 0 ? names('BUSINESS_TYPE').join(', ') : none}</Row>
        <Row term="종목">{names('BUSINESS_ITEM').length > 0 ? names('BUSINESS_ITEM').join(', ') : none}</Row>
      </InfoCard>
      <InfoCard title="3. 주소">
        {detail.addresses.length === 0 ? <Row term="주소">{none}</Row> : detail.addresses.map((a) => (
          <Row key={a.addressType} term={labelIn(ADDRESS_TYPE_LABELS, a.addressType)}>{`(${a.postalCode}) ${a.baseAddress}${a.detailAddress ? ` ${a.detailAddress}` : ''}`}</Row>
        ))}
      </InfoCard>
      <InfoCard title="4. 대표자">
        {detail.representatives.length === 0 ? <Row term="대표자">{none}</Row> : detail.representatives.map((r, i) => (
          <Row key={i} term={`대표자 ${i + 1}`}>{`${r.name} (${labelIn(REPRESENTATIVE_TYPE_LABELS, r.representativeType)})`}</Row>
        ))}
      </InfoCard>
      <InfoCard title="5. 사업체 연락">
        <Row term="전화">{or(registration.phoneNumber, (v) => <span className="font-mono">{formatPhone(v)}</span>)}</Row>
        <Row term="이메일">{or(registration.email)}</Row>
      </InfoCard>
      <InfoCard title="6. 등록 담당자">
        {detail.contacts.length === 0 ? <Row term="담당자">{none}</Row> : detail.contacts.map((c, i) => (
          <Row key={i} term={`담당자 ${i + 1}${c.primary ? ' (기본)' : ''}`}>
            <span>{c.name}</span>
            <span>{c.email}</span>
            <span className="font-mono">{formatPhone(c.phoneNumber)}</span>
          </Row>
        ))}
      </InfoCard>
    </div>
  )
}

/** 이력 행 하나의 표시(저장 시점 마스킹 값 그대로 — §9). */
function rowText(field: string, row: Record<string, unknown>): string {
  const text = (k: string) => (typeof row[k] === 'string' || typeof row[k] === 'number' ? String(row[k]) : '')
  if (field === 'representatives') return `${text('name')}(${labelIn(REPRESENTATIVE_TYPE_LABELS, text('type'))})`
  if (field === 'categories') return `${labelIn(CATEGORY_TYPE_LABELS, text('type'))} ${text('name')}`
  if (field === 'addresses') return `${labelIn(ADDRESS_TYPE_LABELS, text('type'))} ${text('postalCode')} ${text('baseAddress')}${text('detailAddress') ? ` ${text('detailAddress')}` : ''}`
  if (field === 'contacts') return `${text('name')} ${text('email')} ${text('phoneNumber')}${row.primary === true ? ' 기본' : ''}`
  return Object.values(row).map(String).join(' ')
}

function valueText(field: string, value: unknown): string {
  if (value === null || value === undefined) return '없음'
  const s = String(value)
  if (field === 'registrationStatus') return labelIn(REGISTRATION_STATUS_LABELS, s)
  if (field === 'businessType') return labelIn(BUSINESS_TYPE_LABELS, s)
  return s
}

export function changeText(change: BusinessHistoryChange): string {
  const label = HISTORY_FIELD_LABELS[change.field] ?? change.field
  if (change.ended !== undefined || change.added !== undefined) {
    const parts: string[] = []
    if (change.ended && change.ended.length > 0) parts.push(`종료 ${change.ended.map((r) => rowText(change.field, r)).join(', ')}`)
    if (change.added && change.added.length > 0) parts.push(`추가 ${change.added.map((r) => rowText(change.field, r)).join(', ')}`)
    return `${label}: ${parts.join(' / ')}`
  }
  if (!('before' in change)) return `${label}: ${valueText(change.field, change.after)}`
  return `${label}: ${valueText(change.field, change.before)} → ${valueText(change.field, change.after)}`
}

function HistoryTable({ history, onMore }: { history: HistoryState | null; onMore: () => void }) {
  return (
    <div className="flex flex-col gap-3">
      <Table aria-label="변경 이력">
        <TableHeader>
          <TableRow>
            {['시각', '행위자', '종류', '사유', '바뀐 항목'].map((h) => <TableHead key={h}>{h}</TableHead>)}
          </TableRow>
        </TableHeader>
        <TableBody>
          {!history || history.items.length === 0 ? (
            <EmptyRow columns={5} message={history ? '변경 이력이 없습니다.' : '불러오는 중…'} />
          ) : (
            history.items.map((entry) => (
              <TableRow key={entry.seq}>
                <TableCell><KstTime iso={entry.occurredAt} /></TableCell>
                <TableCell><span className="font-mono text-xs break-all">{entry.actorRef}</span></TableCell>
                <TableCell>{CHANGE_TYPE_LABELS[entry.changeType] ?? entry.changeType}</TableCell>
                <TableCell>{entry.reason ?? <span className="text-muted-foreground">없음</span>}</TableCell>
                <TableCell>
                  <ul className="flex flex-col gap-0.5 text-xs">
                    {entry.changes.map((c, i) => <li key={i}>{changeText(c)}</li>)}
                  </ul>
                </TableCell>
              </TableRow>
            ))
          )}
        </TableBody>
      </Table>
      {history?.nextAfter !== null && history?.nextAfter !== undefined && (
        <div className="flex justify-center">
          <Button type="button" variant="outline" onClick={onMore}>더 보기</Button>
        </div>
      )}
    </div>
  )
}
