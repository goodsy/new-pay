import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import type { AdminApi, BusinessDetail, BusinessHistoryChange, BusinessHistoryEntry, BusinessListItem, BusinessLookupItem, BusinessSearchInput, MerchantListItem } from '../api/adminApi'
import { EmptyRow, KstTime, Spinner } from '../components/DataBits'
// 상세 카드·줄은 components/DetailCard.tsx로 그대로 옮김(출력 동일 — 등록 폼 FormRow와 나란히 쓰는 공용 구성요소).
import { DetailRow as Row, InfoCard } from '../components/DetailCard'
import { DetailTabs } from '../components/DetailTabs'
import { ErrorNotice, WarningNote } from '../components/Notice'
import { PageHeader } from '../components/PageHeader'
import { RegistrationNumberInput } from '../components/RegistrationNumberInput'
import { FilterField, FilterPanel } from '../components/FilterPanel'
import { listCountText } from '../lib/listCount'
import { Badge } from '../components/ui/badge'
import { Button } from '../components/ui/button'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '../components/ui/table'
import { SESSION_ENDING, type ConsoleErrorCode } from '../errors/errorCodes'
import { codeOf, ERROR_MESSAGES } from '../errors/messages'
import {
  ADDRESS_TYPE_LABELS, BRN_RATE_LIMITED, BUSINESS_READ_RATE_LIMITED, BUSINESS_TYPE_LABELS, CATEGORY_TYPE_LABELS, CHANGE_TYPE_LABELS, formatCorporateNumber, formatPhone,
  HISTORY_FIELD_LABELS, labelIn, REGISTRATION_STATUS_LABELS, REPRESENTATIVE_TYPE_LABELS, representativeSummary, compactForm, contentOf, contentOfDetail, type BusinessForm,
} from '../lib/businessProfile'
import { defaultSubmissionId, type BusinessRef, type HandoffState } from '../lib/registration'
import { displayRegistrationNumber, isRegistrationNumber } from '../lib/registrationNumber'
import { useRemembered, type ScreenMemory } from '../lib/screenMemory'
import { BusinessProfileFormScreen } from './BusinessProfileFormScreen'
import { BusinessContractProgressSection } from './BusinessContractProgressSection'

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
  /** MERCHANT-IA-001 B5(Q-IA-8 '연결 가맹점' 탭): 가맹점 '상세' → 가맹점 조회 탭의 그 가맹점 상세(App이 화면을 바꾼다 — MERCHANT-DETAIL-VIEW-001 규칙). 없으면 '상세' 버튼 없음. */
  onOpenMerchant?: (merchantId: string) => void
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
/** 사업자 상세 탭(MERCHANT-IA-001 B5 — 기본정보 / 연결 가맹점 (n) / 변경 이력). */
type DetailTabKey = 'info' | 'linked' | 'history'
/** 연결 가맹점(기존 가맹점 검색의 사업자 조건 businessId — keyset '다음'). */
interface LinkedState { businessId: string; items: MerchantListItem[]; next: string | null }
type Leave = { kind: 'list' } | { kind: 'tab'; tab: DetailTabKey } | { kind: 'merchant'; merchantId: string }
/** 일반 검색 결과 열(MERCHANT-IA-001 B2 — 진위확인 열은 늘 '미검증'이라 뺐다, D-ADMIN-UI-010 ②). */
// B6(명세 §4.1): 마지막 열 이름 '보기' → '상세'(가맹점 목록·번호 조회 결과와 같은 이름).
const SEARCH_COLUMNS = ['사업자등록번호', '상호', '대표자', '사업체 구분', '등록 상태', '가맹점 수', '수정일', '상세'] as const
/** 사업자등록번호 조회 결과 열(D-ADMIN-UI-010 ⑦ 짧은 결과 모양 — 번호 조회 응답에 있는 값만). */
const LOOKUP_COLUMNS = ['사업자등록번호', '상호', '등록 상태', '상세'] as const
const NO_FILTERS: Filters = Object.freeze({ displayNamePrefix: '', businessType: '', registrationStatus: '', createdFrom: '', createdTo: '' })
const EDIT_KEY = 'edit'
/** B5 리뷰 MINOR-3: 저장하지 않은 입력이 새로 읽은 정보로 버려진 사업자(안내 한 줄 — 닫거나 다른 화면으로 가면 지운다). */
const DISCARDED_KEY = 'detail.discarded'
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
  // B5 리뷰 MINOR-1: 같은 사업자를 다시 열어도 연결 가맹점은 다시 읽는다(이력과 같은 규칙).
  memory.remove('linked')
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

export function BusinessesScreen({ api, memory, canEdit, onContinue, onRegister, handoffState = () => 'empty', onSessionEnded, newSubmissionId = defaultSubmissionId, onOpenMerchant }: Props) {
  const [view, setView] = useRemembered<View>(memory, 'view', LIST)
  const [filters, setFilters] = useRemembered<Filters>(memory, 'filters', NO_FILTERS)
  const [list, setList] = useRemembered<ListState | null>(memory, 'list', null)
  const [doc, setDoc] = useRemembered<BusinessDetail | null>(memory, 'detail.doc', null)
  const [tab, setTab] = useRemembered<DetailTabKey>(memory, 'detail.tab', 'info')
  const [linked, setLinked] = useRemembered<LinkedState | null>(memory, 'linked', null)
  const [linkedBusy, setLinkedBusy] = useState(false)
  const [linkedError, setLinkedError] = useState<string | null>(null)
  /** B5 이탈 확인(화면 안 — 브라우저 confirm 아님): 저장하지 않은 편집이 있을 때 목록으로·탭 이동·가맹점 상세로 가려 하면 먼저 묻는다. */
  const [leave, setLeave] = useState<Leave | null>(null)
  const [editForm] = useRemembered<BusinessForm | null>(memory, `${EDIT_KEY}.form`, null)
  const [discarded, setDiscarded] = useRemembered<string | null>(memory, DISCARDED_KEY, null)
  const [history, setHistory] = useRemembered<HistoryState | null>(memory, 'history', null)
  // 평문 번호는 화면 자체 상태 — 보낸 즉시 비운다(메모리·저장소·URL 없음).
  const [number, setNumber] = useState('')
  const [numberError, setNumberError] = useState(false)
  const [busy, setBusy] = useState(false)
  // 리뷰 NIT-6: 목록 오류는 메모리에 둔다 — 첫 목록 읽기가 실패한 뒤 탭에 다시 들어와도 자동으로 다시 읽지 않는다("다시 시도" 버튼만).
  const [error, setError] = useRemembered<string | null>(memory, 'list.error', null)
  /** B6 '초기화' 뒤(목록을 비운 상태): 처음 들어올 때의 자동 첫 쪽 읽기를 하지 않는다 — 초기화는 서버 호출이 없는 화면 동작. 다음 '검색'에서 풀린다. */
  const [cleared, setCleared] = useRemembered<boolean>(memory, 'list.cleared', false)
  const numberNoteId = useId()
  const [detailError, setDetailError] = useState<string | null>(null)
  const [handoff, setHandoff] = useState<'locked' | 'confirm' | null>(null)
  const [editBase] = useRemembered<BusinessDetail | null>(memory, EDIT_BASE, null)
  const [editStatus] = useRemembered<{ kind?: string } | null>(memory, `${EDIT_KEY}.cmd.status`, null)

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
    if (view.kind === 'list' && list === null && !cleared && !busy && error === null) void runSearch({}, false)
  }, [view.kind, list])

  // 상세: 선택한 사업자 문서가 없으면 읽는다(열람마다 서버 감사 BUSINESS_VIEWED).
  useEffect(() => {
    if ((view.kind !== 'detail' && view.kind !== 'edit') || (doc && doc.business.businessId === view.businessId)) return
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
    setCleared(false)
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
    setLeave(null)
    // B5 리뷰 NIT-1: 사업자를 바꾸면 연결 가맹점 읽기 상태(진행·오류)도 새로.
    setLinkedBusy(false)
    setLinkedError(null)
    openBusinessIn(memory, businessId)
  }

  /**
   * B5 리뷰 MINOR-2: 이탈 확인의 초점 — 열리면 '계속 편집'으로, '계속 편집'·Esc면 확인을 부른 버튼·탭으로 돌아가고, '탭 옮기기'면 새로 고른 탭으로.
   * (목록·가맹점으로 가면 그 화면의 제목 초점 규칙을 따른다.) 확인 안에서는 Tab이 두 버튼 사이만 돈다.
   */
  const leaveMessageId = useId()
  const leaveTrigger = useRef<HTMLElement | null>(null)
  const keepEditingRef = useRef<HTMLButtonElement>(null)
  const focusAfter = useRef<(() => HTMLElement | null) | null>(null)
  useEffect(() => {
    if (leave) keepEditingRef.current?.focus()
  }, [leave])
  useEffect(() => {
    if (!focusAfter.current) return
    const target = focusAfter.current()
    focusAfter.current = null
    target?.focus()
  })
  const keepEditing = () => {
    const trigger = leaveTrigger.current
    focusAfter.current = () => trigger
    setLeave(null)
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

  /** 연결 가맹점 읽기(기존 가맹점 검색 — 사업자 조건 businessId, keyset '다음'). 감사 없음·가맹점 조회 속도 제한(서버) — 명세 §7.5. */
  const loadLinked = async (businessId: string, more: boolean) => {
    setLinkedBusy(true)
    setLinkedError(null)
    // B5 리뷰 NIT-1: 응답이 오기 전에 다른 사업자를 열었으면 그 응답·오류는 버린다(지금 보는 사업자 것만 쓴다).
    const stillShowing = () => {
      const v = memory.read('view') as View | undefined
      return v !== undefined && v.kind !== 'list' && v.businessId === businessId
    }
    try {
      const after = more && linked?.businessId === businessId ? linked.next ?? undefined : undefined
      const page = await api.merchantSearch(after === undefined ? { businessId } : { businessId, after })
      if (!stillShowing()) return
      const prev = memory.read('linked') as LinkedState | null | undefined
      setLinked({ businessId, items: more && prev?.businessId === businessId ? [...prev.items, ...page.items] : page.items, next: page.nextCursor })
    } catch (e) {
      if (stillShowing()) fail(e, false, setLinkedError)
    } finally {
      if (stillShowing()) setLinkedBusy(false)
    }
  }

  /**
   * B5 편집 기준 고정(리뷰 MINOR-1 규칙 그대로 — 보내는 version은 이 기준): OPERATOR가 상세를 열면 그 문서를 편집 기준(edit.base)으로 고정한다.
   * 결과 확인이 필요한 편집(보내는 중·결과 불명)이 있으면 바꾸지 않는다 — 같은 사업자면 그 기준 그대로, 다른 사업자면 이 사업자는 읽기 표시.
   */
  useEffect(() => {
    if (!canEdit || (view.kind !== 'detail' && view.kind !== 'edit') || !doc || doc.business.businessId !== view.businessId) return
    if (unresolvedEditOf(memory) !== null) return
    const pinned = memory.read(EDIT_BASE) as BusinessDetail | undefined
    if (pinned && pinned.business.businessId === doc.business.businessId && pinned.business.version === doc.business.version) return
    // B5 리뷰 MINOR-3: 다른 화면의 링크·새 version 다시 읽기로 저장하지 않은 입력이 버려지면 한 줄로 알린다(이탈 확인은 더하지 않는다 — 새 정보 기준이 안전한 쪽).
    const unsaved = memory.read(`${EDIT_KEY}.form`) as BusinessForm | undefined
    if (pinned && unsaved && formChanged(pinned, unsaved)) memory.write(DISCARDED_KEY, doc.business.businessId)
    clearEdit(memory)
    memory.write(EDIT_BASE, doc)
  }, [canEdit, view, doc])

  const continueWith = (business: BusinessRef) => {
    const state = handoffState()
    if (state === 'locked') setHandoff('locked')
    else if (state === 'inProgress') setHandoff('confirm')
    else {
      setHandoff(null)
      onContinue(business)
    }
  }

  // ── 상세(MERCHANT-IA-001 B5 — D-ADMIN-UI-011 ②: OPERATOR는 기본정보 탭이 항상 편집 폼, 조회 역할은 같은 배치의 읽기 표시) ──
  // 옛 'edit' 화면(별도 수정 화면)은 이 상세로 흡수했다 — 메모리에 남은 'edit' 화면은 상세로 본다.
  if (view.kind === 'detail' || view.kind === 'edit') {
    const current = doc && doc.business.businessId === view.businessId ? doc : null
    const pendingId = unresolvedEditOf(memory)
    // 편집 기준(edit.base — 리뷰 MINOR-1 규칙 그대로): 결과 확인이 필요한 편집이 이 사업자 것이면 그 기준, 아니면 지금 상세에서 고정한 기준(effect).
    const base = current && editBase && editBase.business.businessId === current.business.businessId ? editBase : null
    const blockedByOther = pendingId !== null && current !== null && pendingId !== current.business.businessId
    // BIZ-NORM-002 M-3(BR-BIZ-311): 사용중지 사업자는 편집 폼을 열지 않는다(읽기 표시 + 안내 — 서버도 BUSINESS_INACTIVE로 거절). 단 이 사업자에 결과 확인이 필요한
    // 편집이 있으면 폼을 유지한다(같은 submissionId 재전송으로 결과를 확인해야 한다 — 결과 불명 잠금 규칙 그대로).
    const inactive = current !== null && current.business.registrationStatus === 'INACTIVE'
    const editable = canEdit && current !== null && base !== null && !blockedByOther && (!inactive || pendingId === current.business.businessId)
    const dirty = editable && editForm !== null && formChanged(base!, editForm) && !(editStatus?.kind === 'busy' || editStatus?.kind === 'unknown')
    const proceed = (to: Leave) => {
      setLeave(null)
      if (to.kind === 'tab') {
        selectTab(to.tab)
        focusAfter.current = () => document.querySelector<HTMLElement>('[role="tablist"][aria-label="사업자 상세 보기"] [role="tab"][aria-selected="true"]')
        return
      }
      setDiscarded(null)
      // 이동하면 저장하지 않은 편집은 버린다(결과 확인이 필요한 편집은 버리지 않는다 — 같은 submissionId 재전송 유지).
      if (unresolvedEditOf(memory) === null) clearEdit(memory)
      if (to.kind === 'list') {
        setHandoff(null)
        setView(LIST)
        focusTitle.current = true
      } else onOpenMerchant?.(to.merchantId)
    }
    const request = (to: Leave) => {
      if (!dirty) {
        proceed(to)
        return
      }
      leaveTrigger.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
      setLeave(to)
    }
    const selectTab = (t: DetailTabKey) => {
      if (!current) return
      setTab(t)
      if (t === 'history' && history?.businessId !== current.business.businessId) void loadHistory(current.business.businessId, false)
      if (t === 'linked' && linked?.businessId !== current.business.businessId) void loadLinked(current.business.businessId, false)
    }
    return (
      <section aria-label="사업자 상세" className="flex flex-col gap-4">
        {/* B5: OPERATOR 편집 폼은 하단 저장 줄에 '목록으로'가 있다(pg-lab식) — 위쪽 버튼은 편집 폼이 없을 때(조회 역할·편집 불가)만. */}
        {!editable && (
          <div>
            <Button type="button" variant="outline" size="sm" onClick={() => request({ kind: 'list' })}>목록으로</Button>
          </div>
        )}
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
            {/* B5(명세 §4.2): 등록일·수정일은 머리 아래 메타 한 줄. */}
            <p className="flex flex-wrap items-center gap-1 text-xs text-muted-foreground">
              <span>최초 저장일</span><KstTime iso={current.business.createdAt} /><span>· 수정</span><KstTime iso={current.business.updatedAt} />
            </p>
            {current.business.registrationStatus === 'DRAFT' && (
              <WarningNote>
                임시저장 사업자에는 새 가맹점을 등록할 수 없습니다. 필수 정보를 입력해 등록 완료하세요(이미 등록된 가맹점·계약·MID는 그대로 동작합니다).
                {current.business.businessType === null ? ' 사업체 구분이 비어 있는 기존 사업자입니다 — 아래 폼에서 구분을 고르세요.' : ''}
              </WarningNote>
            )}
            {canEdit && current.business.registrationStatus === 'REGISTERED' && (
              <div className="flex flex-wrap gap-2">
                <Button type="button" onClick={() => continueWith({ businessId: current.business.businessId, name: current.business.businessName, registrationNumber: current.business.registrationNumber })}>
                  이 사업자로 가맹점 등록
                </Button>
              </div>
            )}
            {canEdit && inactive && (
              <WarningNote>{editable ? '사용중지된 사업자는 수정할 수 없습니다. 결과 확인 뒤 읽기 표시로 바뀝니다.' : '사용중지된 사업자는 수정할 수 없습니다.'}</WarningNote>
            )}
            {canEdit && pendingId === current.business.businessId && (
              <WarningNote>이 사업자에 결과 확인이 필요한 저장이 있습니다 — 아래 폼의 "같은 내용 다시 보내기"로 먼저 확인하세요.</WarningNote>
            )}
            {canEdit && blockedByOther && (
              <div className="flex flex-col gap-2">
                <WarningNote>다른 사업자에 결과 확인이 필요한 저장이 있어 이 사업자는 편집 폼을 열지 않았습니다 — 그 사업자 상세에서 먼저 확인하세요.</WarningNote>
                <div><Button type="button" variant="outline" size="sm" onClick={() => openDetail(pendingId!)}>결과 확인이 필요한 사업자 열기</Button></div>
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
            {discarded === current.business.businessId && (
              <div role="status" aria-label="버려진 입력" className="flex flex-wrap items-center gap-2">
                <WarningNote>저장하지 않은 입력은 새로 읽은 사업자 정보로 다시 불러와 버려졌습니다. 필요하면 다시 입력하세요.</WarningNote>
                <Button type="button" variant="ghost" size="sm" onClick={() => setDiscarded(null)}>닫기</Button>
              </div>
            )}
            {leave && (
              <div
                role="alertdialog"
                aria-label="저장하지 않은 변경"
                aria-describedby={leaveMessageId}
                className="flex flex-col gap-2 rounded-md border border-warning/40 bg-warning/10 p-3 text-sm"
                onKeyDown={(event) => {
                  if (event.key === 'Escape') {
                    event.preventDefault()
                    keepEditing()
                    return
                  }
                  if (event.key !== 'Tab') return
                  // 확인 안에서만 초점이 돈다(두 버튼).
                  const buttons = Array.from(event.currentTarget.querySelectorAll<HTMLButtonElement>('button'))
                  const at = buttons.indexOf(document.activeElement as HTMLButtonElement)
                  event.preventDefault()
                  buttons[(at + (event.shiftKey ? buttons.length - 1 : 1)) % buttons.length]?.focus()
                }}
              >
                <p id={leaveMessageId} className="m-0 font-medium">
                  {leave.kind === 'tab' ? '저장하지 않은 변경이 있습니다. 탭을 옮겨도 입력은 기본정보 탭에 남습니다.' : '저장하지 않은 변경이 있습니다. 이동하면 입력한 내용이 사라집니다.'}
                </p>
                <div className="flex flex-wrap gap-2">
                  <Button ref={keepEditingRef} type="button" variant="outline" size="sm" onClick={keepEditing}>계속 편집</Button>
                  <Button type="button" variant={leave.kind === 'tab' ? 'outline' : 'destructive'} size="sm" onClick={() => proceed(leave)}>{leave.kind === 'tab' ? '탭 옮기기' : '변경 버리고 이동'}</Button>
                </div>
              </div>
            )}
            {/* MERCHANT-IA-001 B4·B5: 공용 DetailTabs — 기본정보 / 연결 가맹점 (n) / 변경 이력. 이력·연결 가맹점은 그 탭을 처음 고를 때 읽는다. 패널은 hidden(언마운트 없음 — 입력 유지). */}
            <DetailTabs<DetailTabKey>
              label="사업자 상세 보기"
              selected={tab === 'linked' || tab === 'history' ? tab : 'info'}
              onSelect={(t) => (tab === 'info' && t !== 'info' ? request({ kind: 'tab', tab: t }) : selectTab(t))}
              tabs={[
                {
                  key: 'info',
                  label: '기본정보',
                  panel: editable ? (
                    <BusinessProfileFormScreen
                      key={`${base!.business.businessId}:${base!.business.version}`}
                      api={api}
                      mode={{ kind: 'edit', detail: base! }}
                      layout="detail"
                      memory={memory}
                      memoryKey={EDIT_KEY}
                      newSubmissionId={newSubmissionId}
                      onSessionEnded={onSessionEnded}
                      onSaved={(businessId) => {
                        clearEdit(memory)
                        openDetail(businessId)
                      }}
                      onOpenBusiness={openDetail}
                      onReload={() => {
                        clearEdit(memory)
                        openDetail(current.business.businessId)
                      }}
                      onAbandon={() => {
                        // 결과 불명 포기: 편집 메모리를 비우고 상세를 다시 읽는다(다음 편집은 최신 상세가 기준).
                        clearEdit(memory)
                        openDetail(current.business.businessId)
                      }}
                      onBack={() => request({ kind: 'list' })}
                    />
                  ) : <DetailInfo detail={current} />,
                },
                {
                  key: 'linked',
                  label: `연결 가맹점 (${current.business.merchantCount})`,
                  panel: (
                    <LinkedMerchants
                      state={linked?.businessId === current.business.businessId ? linked : null}
                      busy={linkedBusy}
                      error={linkedError}
                      onMore={() => loadLinked(current.business.businessId, true)}
                      onOpen={onOpenMerchant ? (merchantId) => request({ kind: 'merchant', merchantId }) : undefined}
                    />
                  ),
                },
                { key: 'history', label: '변경 이력', panel: <HistoryTable history={history?.businessId === current.business.businessId ? history : null} onMore={() => loadHistory(current.business.businessId, true)} /> },
              ]}
            />
            {/* MERCHANT-FLOW-001 FLOW-1: 읽기 전용 '계약 진행' 섹션 — 탭 패널 밖(하단 저장 편집 폼에 묶이지 않는 별도 섹션, D-FLOW-007 ② 섹션 원칙), 상세 문서와 같은 응답. */}
            <BusinessContractProgressSection progress={current.contractProgress} />
          </>
        )}
      </section>
    )
  }

  // ── 목록 ──
  const set = (patch: Partial<Filters>) => setFilters({ ...filters, ...patch })
  /** B6 '초기화': 입력·오류·결과를 비우는 화면 동작만(서버 호출 없음 — 자동 첫 쪽 읽기도 막는다). 검색 중에는 버튼이 막혀 있다. */
  const resetSearch = () => {
    setFilters(NO_FILTERS)
    setNumber('')
    setNumberError(false)
    setError(null)
    setCleared(true)
    setList(null)
  }
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
      {/* MERCHANT-IA-001 B6(사용자 결정 D-ADMIN-UI-011 ① — pg-lab 목록 패턴, 명세 §4.1): 검색 조건을 한 카드 안 '항목명 | 입력' 칸으로 묶고 아래 오른쪽 '초기화'·'검색'('조회' → '검색' 용어 통일).
          번호를 넣으면 다른 조건은 비활성·번호는 보낸 즉시 비움·검색 조건과 호출 인자는 그대로. 감사 안내는 묶음 아래 한 줄. */}
      <FilterPanel label="사업자 검색" onSubmit={search} onReset={resetSearch} busy={busy}>
        <FilterField label="사업자등록번호" error={numberError ? '사업자등록번호는 숫자 10자리입니다.' : null} describedBy={numberNoteId}>
          {(id, describedBy) => <RegistrationNumberInput plain id={id} placeholder="000-00-00000" value={number} onValueChange={(v) => { setNumber(v); setNumberError(false) }} aria-describedby={describedBy} aria-invalid={numberError || undefined} />}
        </FilterField>
        <FilterField label="상호">
          {(id) => <input id={id} value={filters.displayNamePrefix} disabled={byNumber} autoComplete="off" placeholder="앞부분 일치" onChange={(e) => set({ displayNamePrefix: e.target.value })} />}
        </FilterField>
        <FilterField label="사업체 구분">
          {(id) => (
            <select id={id} value={filters.businessType} disabled={byNumber} onChange={(e) => set({ businessType: e.target.value })}>
              <option value="">전체</option>
              <option value="INDIVIDUAL">개인</option>
              <option value="CORPORATION">법인</option>
            </select>
          )}
        </FilterField>
        <FilterField label="등록 상태">
          {(id) => (
            <select id={id} value={filters.registrationStatus} disabled={byNumber} onChange={(e) => set({ registrationStatus: e.target.value })}>
              <option value="">전체</option>
              <option value="DRAFT">임시저장</option>
              <option value="REGISTERED">등록 완료</option>
            </select>
          )}
        </FilterField>
        {/* D-GOV-008 ②: '등록일' → '최초 저장일'(검색 조건·호출 인자 createdFrom/createdTo 그대로). */}
        <FilterField label="최초 저장일" group>
          {() => (
            <>
              <input type="date" aria-label="최초 저장일 시작" value={filters.createdFrom} disabled={byNumber} onChange={(e) => set({ createdFrom: e.target.value })} />
              <span className="rangesep" aria-hidden="true">~</span>
              <input type="date" aria-label="최초 저장일 끝" value={filters.createdTo} disabled={byNumber} onChange={(e) => set({ createdTo: e.target.value })} />
            </>
          )}
        </FilterField>
      </FilterPanel>
      <p id={numberNoteId} className="text-xs text-muted-foreground">사업자등록번호 조회는 매번 감사 기록에 남습니다. 번호로 찾으면 다른 조건은 쓰지 않습니다.</p>
      {cleared && list === null && !error && <p className="text-sm text-muted-foreground">검색 조건을 넣고 '검색'을 누르세요.</p>}
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
          {/* B6: 표 아래 건수(pg-lab '총 n건' — keyset이라 다음 쪽이 있으면 "n건 표시 · 더 있음")와 '더 보기'. */}
          <div className="flex items-center justify-between gap-3 text-xs text-muted-foreground">
            <span>{listCountText(list.items.length, list.next !== null)}</span>
            {list.next && <Button type="button" variant="outline" disabled={busy} onClick={() => runSearch(list.criteria, true)}>더 보기</Button>}
          </div>
        </>
      )}
    </section>
  )
}

/** 편집 폼이 기준 상세와 달라졌는가(BusinessProfileFormScreen의 `changed`와 같은 계산 — 이탈 확인용). */
function formChanged(base: BusinessDetail, form: BusinessForm): boolean {
  return JSON.stringify(contentOfDetail(base)) !== JSON.stringify(contentOf(compactForm(form)))
}

/**
 * 연결 가맹점 탭(MERCHANT-IA-001 B5 — D-ADMIN-UI-010 ⑧): 가맹점(이름·코드) | MID 수 | 등록일 | 상세. 내부 키(mer_)는 그리지 않는다(행 이름은 가맹점 코드).
 * '상세'는 가맹점 조회 탭의 그 가맹점 상세로(App — 상세 조회 감사는 그 화면이 연다).
 */
function LinkedMerchants({ state, busy, error, onMore, onOpen }: {
  state: LinkedState | null
  busy: boolean
  error: string | null
  onMore: () => void
  onOpen?: (merchantId: string) => void
}) {
  return (
    <div className="flex flex-col gap-3">
      {error && <ErrorNotice>{error}</ErrorNotice>}
      {busy && state === null && <p className="text-sm text-muted-foreground"><Spinner active /> 불러오는 중…</p>}
      {state && (
        <Table aria-label="연결 가맹점 목록">
          <TableHeader>
            <TableRow>
              <TableHead>가맹점</TableHead><TableHead>MID 수</TableHead><TableHead>등록일</TableHead>{onOpen && <TableHead>상세</TableHead> /* B6: 작업 열 이름 '상세'(목록들과 같은 이름 — 보이는 머리) */}
            </TableRow>
          </TableHeader>
          <TableBody>
            {state.items.length === 0 && <EmptyRow columns={onOpen ? 4 : 3} message="연결된 가맹점이 없습니다." />}
            {state.items.map((m) => (
              <TableRow key={m.merchantId} aria-label={m.merchantCode}>
                <TableCell><span className="flex flex-col"><span className="font-medium">{m.merchantName}</span><span className="font-mono text-xs">{m.merchantCode}</span></span></TableCell>
                <TableCell>{m.midCount}</TableCell>
                <TableCell><KstTime iso={m.createdAt} /></TableCell>
                {onOpen && <TableCell><Button type="button" variant="outline" size="sm" aria-label={`${m.merchantName} 가맹점 상세`} onClick={() => onOpen(m.merchantId)}>상세</Button></TableCell>}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )}
      {state?.next && (
        <div className="flex justify-center">
          <Button type="button" variant="outline" disabled={busy} onClick={onMore}>더 보기</Button>
        </div>
      )}
    </div>
  )
}

/**
 * 사업자 상세 읽기 표시(MERCHANT-IA-001 B5 — D-ADMIN-UI-011 ②: OPERATOR의 편집 폼과 같은 배치 — 섹션 회사(2열) / 대표자 / 담당자). 조회 역할(APPROVER 등)과,
 * 결과 확인이 필요한 다른 사업자 편집이 있어 이 사업자 폼을 열 수 없을 때 쓴다. 값은 원문(D-BIZ-006 ②), 사업자등록번호는 원문 하이픈(D-BIZ-007).
 */
function DetailInfo({ detail }: { detail: BusinessDetail }) {
  const { business, registration } = detail
  const names = (type: string) => detail.categories.filter((c) => c.categoryType === type).map((c) => c.name)
  const none = <span className="text-muted-foreground">미입력</span>
  const or = (value: string | null | undefined, show: (v: string) => ReactNode = (v) => v) => (value ? show(value) : none)
  return (
    <div className="flex flex-col gap-4">
      <section aria-label="회사" className="flex flex-col gap-1 rounded-xl border bg-card px-4 py-3">
        <h3 className="text-sm font-semibold">회사</h3>
        <div className="grid gap-x-8 lg:grid-cols-2">
          <dl>
            <Row term="사업체 구분">{labelIn(BUSINESS_TYPE_LABELS, business.businessType)}</Row>
            <Row term="사업자등록번호"><span className="font-mono">{displayRegistrationNumber(business.registrationNumber)}</span></Row>
            <Row term="상호">{business.businessName}</Row>
            {/* MERCHANT-IA-001 B2(D-ADMIN-UI-010 ②, 명세 §3.10): '등록 상태'는 머리 배지와 중복, '진위확인'(늘 미검증)·'국가'(늘 KR)는 늘 같은 값이라 뺐다(값·API 그대로). */}
            {business.businessType === 'CORPORATION' && <Row term="법인등록번호">{or(registration.corporateRegistrationNumber, (v) => <span className="font-mono">{formatCorporateNumber(v)}</span>)}</Row>}
            <Row term="개업일">{or(registration.openingDate)}</Row>
            <Row term="영문 상호">{or(registration.englishName)}</Row>
          </dl>
          <dl>
            <Row term="업태">{names('BUSINESS_TYPE').length > 0 ? names('BUSINESS_TYPE').join(', ') : none}</Row>
            <Row term="종목">{names('BUSINESS_ITEM').length > 0 ? names('BUSINESS_ITEM').join(', ') : none}</Row>
            <Row term="사업체 전화">{or(registration.phoneNumber, (v) => <span className="font-mono">{formatPhone(v)}</span>)}</Row>
            <Row term="사업체 이메일">{or(registration.email)}</Row>
            <Row term="가맹점 수">{business.merchantCount}</Row>
          </dl>
        </div>
        <dl>
          {detail.addresses.length === 0 ? <Row term="주소">{none}</Row> : detail.addresses.map((a) => (
            <Row key={a.addressType} term={labelIn(ADDRESS_TYPE_LABELS, a.addressType)}>{`(${a.postalCode}) ${a.baseAddress}${a.detailAddress ? ` ${a.detailAddress}` : ''}`}</Row>
          ))}
        </dl>
      </section>
      <InfoCard title="대표자">
        {detail.representatives.length === 0 ? <Row term="대표자">{none}</Row> : detail.representatives.map((r, i) => (
          <Row key={i} term={`대표자 ${i + 1}`}>{`${r.name} (${labelIn(REPRESENTATIVE_TYPE_LABELS, r.representativeType)})`}</Row>
        ))}
      </InfoCard>
      <InfoCard title="담당자">
        {/* D-GOV-008 ④: 줄 이름 '등록 담당자 n'·'(기본 등록 담당자)'. 섹션 제목 '담당자'는 D-ADMIN-UI-010 ⑥ 그대로. */}
        {detail.contacts.length === 0 ? <Row term="등록 담당자">{none}</Row> : detail.contacts.map((c, i) => (
          <Row key={i} term={`등록 담당자 ${i + 1}${c.primary ? ' (기본 등록 담당자)' : ''}`}>
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
