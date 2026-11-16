/** 업무 모듈 화면(공통 ModulePage 템플릿으로 렌더링) */
export type ModuleId =
  | 'merchant'
  | 'source'
  | 'payment'
  | 'acquire'
  | 'settle'
  | 'risk'
  | 'report'
  | 'ops';

/** 라우트 단위 페이지 ID. URL `/:pageId` 와 1:1 */
export type PageId = 'home' | ModuleId | 'access' | 'map';

/**
 * 목록 1행 = 6칸 고정 튜플 (원본 conf[*].rows 구조 그대로)
 * [0] 대표명  [1] 식별키(MID/TID/정산번호… 상세 조회 키)  [2] 컬럼1  [3] 컬럼2  [4] 상태  [5] 마지막 컬럼(금액/담당자, 우측정렬)
 * 화면 표시 컬럼 cols 는 5개: cols[0] 은 [0]+[1] 2줄 셀, cols[1..4] 는 [2..5]
 */
export type Row = [string, string, string, string, string, string];

export interface ModuleConf {
  /** 페이지 상단 설명 */
  sub: string;
  /** 카드 상단 탭 목록 */
  tabs: string[];
  /** 테이블 헤더 5개 (마지막 컬럼은 .amount 우측정렬) */
  cols: [string, string, string, string, string];
  rows: Row[];
  /** 우상단 primary 버튼 라벨 */
  action: string;
}

/** 사이드바 메뉴: [id, 라벨, SVG path(d)] */
export interface MenuItem {
  id: Exclude<PageId, 'map'>;
  label: string;
  iconPath: string;
}

/** legacy.json 1행 (기존 관리자 메뉴 165개) */
export interface LegacyMenu {
  /** 트리 접두어 포함 메뉴명 (예: "│  ├─ 계약 진행현황") */
  A: string;
  /** MENU_ID */
  B: string;
  /** 레벨 "0"(공통) | "1" | "2" | "3" */
  C: string;
  /** 상위 MENU_ID (트리 기준) */
  D: string;
  /** 상위 MENU_ID (원본 값) */
  E: string;
  /** 기존 화면 URL */
  F: string;
  /** 검토 사항 */
  G: string;
}
