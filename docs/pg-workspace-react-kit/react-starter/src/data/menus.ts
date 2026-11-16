import type { MenuItem, PageId } from './types';

/** 사이드바 메뉴 (순서 = 화면 순서). 아이콘은 24x24 viewBox, stroke 기반 path */
export const MENUS: MenuItem[] = [
  { id: 'home', label: '운영 홈', iconPath: 'M3 10l9-7 9 7v11H3z M9 21v-8h6v8' },
  { id: 'merchant', label: '사업자·가맹점', iconPath: 'M4 21V4h16v17 M8 8h2m4 0h2M8 12h2m4 0h2M9 21v-5h6v5' },
  { id: 'source', label: '원천사·기준정보', iconPath: 'M5 3h14v18H5z M8 7h8M8 11h8M8 15h5' },
  { id: 'payment', label: '결제 관리', iconPath: 'M3 5h18v14H3z M3 10h18M6 15h4' },
  { id: 'acquire', label: '매입·대사', iconPath: 'M3 7h16m-4-4 4 4-4 4M21 17H5m4-4-4 4 4 4' },
  { id: 'settle', label: '정산·지급', iconPath: 'M3 20h18M5 20V8l7-5 7 5v12M9 10v6m6-6v6' },
  { id: 'risk', label: '리스크 관리', iconPath: 'M12 3l8 3v6c0 5-8 9-8 9s-8-4-8-9V6z M12 7v6m0 3v1' },
  { id: 'report', label: '통계·리포트', iconPath: 'M4 20V10h4v10m4 0V4h4v16m4 0V7' },
  { id: 'ops', label: '운영·고객지원', iconPath: 'M4 4h16v12H4z M8 20h8M12 16v4' },
  { id: 'access', label: '계정·권한', iconPath: 'M16 7a4 4 0 1 1-8 0 4 4 0 1 1 8 0M4 21v-3c0-6 16-6 16 0v3' },
];

/** 사이드바 그룹 라벨: 해당 인덱스 메뉴 "앞"에 출력 */
export const NAV_GROUP_LABELS: Record<number, string> = {
  1: 'PAYMENT OPERATIONS',
  7: 'WORKSPACE',
};

/** 메뉴 우측 뱃지(em) 카운트 */
export const NAV_BADGES: Partial<Record<PageId, number>> = { risk: 3 };

export const PAGE_IDS: PageId[] = [...MENUS.map((m) => m.id), 'map'];

export const isPageId = (v: string | undefined): v is PageId => !!v && (PAGE_IDS as string[]).includes(v);

export const pageTitle = (id: PageId): string => MENUS.find((m) => m.id === id)?.label ?? '메뉴 개편 지도';
