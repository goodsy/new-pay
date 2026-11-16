// UI 재현(ui-reference) 샘플 데이터 — docs/ui-reference/reference/app.js의 menus·conf·tabFilters를 그대로 옮겼다.
// 화면 설계용 샘플이며 실제 결제·지급·API와 연결하지 않는다. 문구·순서·SVG path는 원본 그대로 둔다(재현 비교용 레거시 용어 포함).

export type PageId = 'home' | 'merchant' | 'source' | 'payment' | 'acquire' | 'settle' | 'risk' | 'report' | 'ops' | 'access' | 'map'
export type ConfId = 'merchant' | 'source' | 'payment' | 'acquire' | 'settle' | 'risk' | 'report' | 'ops'
export type Row = readonly [string, string, string, string, string, string]
export type Menu = readonly [PageId, string, string]

export const menus: readonly Menu[] = [
  ['home', '운영 홈', 'M3 10l9-7 9 7v11H3z M9 21v-8h6v8'],
  ['merchant', '사업자·가맹점', 'M4 21V4h16v17 M8 8h2m4 0h2M8 12h2m4 0h2M9 21v-5h6v5'],
  ['source', '원천사·기준정보', 'M5 3h14v18H5z M8 7h8M8 11h8M8 15h5'],
  ['payment', '결제 관리', 'M3 5h18v14H3z M3 10h18M6 15h4'],
  ['acquire', '매입·대사', 'M3 7h16m-4-4 4 4-4 4M21 17H5m4-4-4 4 4 4'],
  ['settle', '정산·지급', 'M3 20h18M5 20V8l7-5 7 5v12M9 10v6m6-6v6'],
  ['risk', '리스크 관리', 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M12 7v6m0 3v1'],
  ['report', '통계·리포트', 'M4 20V10h4v10m4 0V4h4v16m4 0V7'],
  ['ops', '운영·고객지원', 'M4 4h16v12H4z M8 20h8M12 16v4'],
  ['access', '계정·권한', 'M16 7a4 4 0 1 1-8 0 4 4 0 1 1 8 0M4 21v-3c0-6 16-6 16 0v3'],
]

export interface Conf {
  sub: string
  tabs: readonly string[]
  cols: readonly string[]
  rows: readonly Row[]
  action: string
}

export const confIds: readonly ConfId[] = ['merchant', 'source', 'payment', 'acquire', 'settle', 'risk', 'report', 'ops']

export const conf: Readonly<Record<ConfId, Conf>> = {
  merchant: {
    sub: '사업자 등록부터 서비스 개통까지, 가맹점의 전체 여정을 관리하세요.',
    tabs: ['가맹점 목록', '계약·온보딩', 'GID / VID', '수수료·정산정책', '변경 이력'],
    cols: ['가맹점 / MID', '사업자번호', '원천사', '상태', '담당자'],
    rows: [
      ['모노마켓', 'mono000001', '123-45-67890', 'NICE', '운영 중', '김 운영'],
      ['오브젝트 스토어', 'obj000002', '234-56-78901', '키움페이', '심사 중', '이 영업'],
      ['데일리 커피', 'daily00003', '345-67-89012', 'NICE', '설정 대기', '박 영업'],
      ['그린리빙', 'green00004', '456-78-90123', '키움페이', '운영 중', '김 운영'],
    ],
    action: '사업자 등록',
  },
  source: {
    sub: '원천사 계약과 채번, 결제수단별 연결 정보를 한곳에서 관리하세요.',
    tabs: ['원천사 연결·채번', '수수료·계약', '서브몰 등록', '카드·BIN·무이자', '은행·계좌풀'],
    cols: ['원천사 / 관리코드', '결제수단', '원천사 MID', '상태', '담당자'],
    rows: [
      ['NICE', 'SRC-001', '신용카드', 'nice_sub_001', '연결 완료', '김 운영'],
      ['키움페이', 'SRC-002', '신용카드', 'kiwoom_sub_002', '심사 중', '이 운영'],
      ['샘플은행', 'SRC-003', '가상계좌', 'bank_vacct_001', '연결 완료', '박 운영'],
    ],
    action: '원천사 등록',
  },
  payment: {
    sub: '승인부터 취소·환불까지, 하나의 거래 타임라인으로 확인하세요.',
    tabs: ['전체 거래', '실패·취소요청', '가상계좌·환불', '정기결제', '현금영수증·에스크로', 'SMS·URL결제'],
    cols: ['가맹점 / 거래번호', '결제수단', '거래일시', '상태', '금액'],
    rows: [
      ['모노마켓', 'T202611020001', '신용카드', '11.02 14:32:08', '승인 완료', '128,000원'],
      ['오브젝트 스토어', 'T202611020002', '신용카드', '11.02 14:28:12', '부분 취소', '64,000원'],
      ['데일리 커피', 'T202611020003', '가상계좌', '11.02 14:25:03', '입금 대기', '35,000원'],
      ['그린리빙', 'T202611020004', '문화상품권', '11.02 14:21:56', '승인 완료', '89,000원'],
    ],
    action: '조회 결과 내보내기',
  },
  acquire: {
    sub: '매입 검증, 반송, 원천사 입금과 거래 차이를 확인하세요.',
    tabs: ['매입 현황', '반송·재매입', '거래대사', '입금대사', '영중소 차액매입'],
    cols: ['원천사 / 배치번호', '대상일', '대상 건수', '상태', '차이 금액'],
    rows: [
      ['NICE', 'ACQ-20261102-01', '2026.11.01', '2,104건', '검증 완료', '0원'],
      ['키움페이', 'ACQ-20261102-02', '2026.11.01', '824건', '차이 발생', '128,000원'],
      ['NICE · 영중소', 'ACQ-20261102-03', '2026.11.01', '45건', '반송', '64,000원'],
    ],
    action: '대사 실행 요청',
  },
  settle: {
    sub: '정산 검증부터 승인과 지급까지, 마감 업무를 놓치지 마세요.',
    tabs: ['정산 현황', '지급 검증·승인', '보류·해제·가감', '가맹점 정산', '대리점 정산', '셀러 지급대행', '세금계산서'],
    cols: ['정산 대상 / 정산번호', '정산일', '거래 건수', '상태', '지급 예정액'],
    rows: [
      ['모노마켓', 'ST-20261102-001', '2026.11.02', '1,204건', '승인 대기', '45,280,000원'],
      ['오브젝트 스토어', 'ST-20261102-002', '2026.11.02', '842건', '검증 완료', '32,640,000원'],
      ['그린리빙', 'ST-20261102-003', '2026.11.02', '368건', '지급 보류', '12,850,000원'],
      ['데일리 커피', 'ST-20261102-004', '2026.11.02', '215건', '지급 완료', '8,320,000원'],
    ],
    action: '지급 승인 요청',
  },
  risk: {
    sub: '이상 징후와 한도, 민원을 연결해 가맹점의 위험을 관리하세요.',
    tabs: ['위험 모니터링', '승인·정산 한도', '증액 요청', '민원·분쟁'],
    cols: ['가맹점 / 탐지번호', '탐지 유형', '관측값', '상태', '담당자'],
    rows: [
      ['그린리빙', 'RM-20261102-01', '거래 급증', '최근 7일 대비 320%', '확인 필요', '김 운영'],
      ['모노마켓', 'RM-20261102-02', '정산한도 소진', '소진율 92%', '검토 중', '이 심사'],
      ['오브젝트 스토어', 'RM-20261102-03', '취소 비율 증가', '취소율 18%', '확인 필요', '박 심사'],
    ],
    action: '검토 이력 작성',
  },
  report: {
    sub: '거래, 손익, 미수·미지급을 같은 기준으로 비교하세요.',
    tabs: ['거래·손익', '원천사별', '가맹점별', '미수·미지급', '세무자료'],
    cols: ['구분 / 기준월', '매출액', '원가', '상태', '수익'],
    rows: [
      ['신용카드', '2026-11', '128,400,000원', '3,210,000원', '집계 완료', '642,000원'],
      ['가상계좌', '2026-11', '32,800,000원', '164,000원', '집계 완료', '98,400원'],
    ],
    action: '조회 결과 내보내기',
  },
  ops: {
    sub: '배치 실행과 통보 실패, 고객 문의를 빠르게 추적하세요.',
    tabs: ['배치·모니터링', '통보·메일·SMS', '고객문의', '공지·FAQ', '영업일·공통코드'],
    cols: ['업무 / 실행번호', '실행 시각', '처리량', '상태', '담당자'],
    rows: [
      ['일 정산 생성', 'JOB-20261102-01', '11.02 06:00', '3,125 / 3,125', '완료', '정산 운영'],
      ['거래내역 메일 발송', 'JOB-20261102-02', '11.02 09:00', '238 / 240', '재시도 필요', '운영 지원'],
      ['원천사 대사', 'JOB-20261102-03', '11.02 09:30', '2,928 / 2,928', '완료', '정산 운영'],
    ],
    action: '실행 이력 내보내기',
  },
}

type TabFilter = ((r: Row) => boolean) | null
export const tabFilters: Partial<Record<ConfId, readonly TabFilter[]>> = {
  merchant: [null, (r) => r[4] !== '운영 중'],
  payment: [null, (r) => /취소|실패/.test(r[4]), (r) => r[2] === '가상계좌', () => false, () => false, () => false],
  acquire: [null, (r) => r[4] === '반송'],
  settle: [null, (r) => /검증|승인/.test(r[4]), (r) => r[4] === '지급 보류'],
  risk: [null, (r) => r[2].includes('한도'), () => false, () => false],
  ops: [null, (r) => r[0].includes('메일'), () => false, () => false],
}

export const ALL_STATUS = '전체 상태'

export function isConfId(id: string): id is ConfId {
  return (confIds as readonly string[]).includes(id)
}

export function isPageId(id: string): id is PageId {
  return isConfId(id) || id === 'home' || id === 'access' || id === 'map'
}

export function title(id: PageId): string {
  return menus.find((m) => m[0] === id)?.[1] || '메뉴 개편 지도'
}

export function badgeTone(s: string): string {
  return /완료|운영 중/.test(s) ? 'green' : /보류|반송|차이|필요/.test(s) ? 'orange' : 'blue'
}

export function filterRows(rows: readonly Row[], id: ConfId, tab: number, query: string, status: string): Row[] {
  const f = tabFilters[id]?.[tab]
  return rows.filter((r) => (!f || f(r)) && r.join(' ').toLowerCase().includes(query.toLowerCase()) && (status === ALL_STATUS || r[4] === status))
}

/** 오늘의 정산 흐름(app.js pipeline) */
export const pipelineSteps: readonly (readonly [string, string, string, string])[] = [
  ['✓', '정산 데이터 생성', '06:00 · 3,125건 생성 완료', '완료'],
  ['✓', '지급 데이터 검증', '09:20 · 금액·계좌 검증 완료', '완료'],
  ['3', '지급 승인', '담당자 승인 대기', '진행 중'],
  ['4', '송금 및 결과 확인', '승인 완료 후 진행', '대기'],
]

export interface LegacyRow {
  A: string
  B: string
  C: string
  D: string
  E: string
  F: string
  G: string
}

/** app.js destination(): 레거시 MENU_ID → 개편 후 업무 영역 */
export function destination(r: LegacyRow): PageId {
  const id = r.B
  if (r.C === '0') return 'access'
  if (/^1104/.test(id)) return 'ops'
  if (/^11/.test(id) || /^1201|1205/.test(id)) return 'merchant'
  if (/^12/.test(id)) return 'source'
  if (/^1312|21/.test(id)) return 'acquire'
  if (/^130908|14/.test(id)) return 'settle'
  if (/^13/.test(id)) return 'payment'
  if (/^15/.test(id)) return 'risk'
  if (/^16/.test(id)) return 'report'
  if (/^170|1803/.test(id)) return 'ops'
  return 'access'
}

export const roles = ['운영 담당자', '정산 담당자', '승인 관리자', '조회 전용'] as const
