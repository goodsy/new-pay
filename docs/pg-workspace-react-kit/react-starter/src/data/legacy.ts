import legacyJson from './legacy.json';
import type { LegacyMenu, PageId } from './types';

/** 기존 관리자 메뉴 165개 (원본 legacy.json 그대로) */
export const LEGACY_MENUS = legacyJson as LegacyMenu[];

/** 기존 MENU_ID → 개편 후 업무 영역 매핑 (원본 destination() 규칙, 판정 순서가 의미를 가짐) */
export function legacyDestination(r: LegacyMenu): PageId {
  const id = r.B;
  if (r.C === '0') return 'access';
  if (/^1104/.test(id)) return 'ops';
  if (/^11/.test(id) || /^1201|1205/.test(id)) return 'merchant';
  if (/^12/.test(id)) return 'source';
  if (/^1312|21/.test(id)) return 'acquire';
  if (/^130908|14/.test(id)) return 'settle';
  if (/^13/.test(id)) return 'payment';
  if (/^15/.test(id)) return 'risk';
  if (/^16/.test(id)) return 'report';
  if (/^170|1803/.test(id)) return 'ops';
  return 'access';
}
