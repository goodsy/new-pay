/**
 * 가맹점 변경 이력 "변경" 열의 한국어 이름(MERCHANT-IA-001 B1 — 명세 docs/repository/MERCHANT-IA-001-SCREEN-ITEMS.md rev 0.2 §3.8 "변경: 한국어 라벨만").
 * 키는 MASTER 변경 기록의 changeType(apps/master-api 변경 기록, 2026-11-24 기준 전부). 표시만 — 이력 값·API·감사는 그대로다.
 * 용어는 "대상" 열 이름([HISTORY_TARGET_LABELS] — Site·서비스 약정(CSA)·카드사 Route 개통)과 맞춘다.
 * `CODE_LABELS`(서버 상태 code 집합 시험 대상)와 섞지 않는 이력 전용 맵이다(historyTarget.ts와 같은 이유).
 */
export const MERCHANT_CHANGE_TYPE_LABELS: Readonly<Record<string, string>> = {
  BusinessCreated: '사업자 생성',
  BusinessProfileUpdated: '사업자 정보 수정',
  BusinessRegistrationCompleted: '사업자 등록 완료',
  // 옛 사업자 등록 경로(BIZ-4 전환에서 제거 — C-27)가 남긴 이력 행(사업자 이력 CHANGE_TYPE_LABELS와 같은 뜻).
  BusinessRegistered: '사업자 등록(이전 경로)',
  MerchantRegistered: '가맹점 등록',
  MerchantCodeChanged: '가맹점 코드 변경',
  // MERCHANT-IA-001 B6(B1 리뷰 NIT-2 — 용어 통일): '사이트' → 'Site'(관리자 화면·명세·결정 문서에서 더 많이 쓰는 말 — 명세 §7.7).
  MerchantSiteRegistered: 'Site 등록',
  MerchantSiteReviewDecided: 'Site 심사 결정',
  ContractRegistered: '계약 등록',
  ContractRevisionRegistered: '계약 Revision 등록',
  ContractRevisionApproved: '계약 Revision 승인',
  ServiceAgreementAdded: '서비스 약정 추가',
  MerchantAccountIssued: 'MID 발급',
  DefaultMerchantAccountDesignated: '기본 MID 지정',
  SiteAssignmentStarted: 'Site 배정 시작',
  PaymentMethodActivationRegistered: '결제수단 개통 등록',
  PaymentMethodActivationApproved: '결제수단 개통 승인',
  PaymentMethodActivationSuspended: '결제수단 개통 정지',
  PaymentMethodActivationResumed: '결제수단 개통 재개',
  PaymentMethodActivationTerminated: '결제수단 개통 종료',
  SiteActivationConfirmed: 'Site 적용 확인',
  SiteActivationConfirmationRevoked: 'Site 적용 확인 철회',
  CurrencyEnabled: '통화 활성화',
  CurrencyEnablementEnded: '통화 활성화 종료',
  CardRouteActivationRegistered: '카드사 Route 개통 등록',
  CardRouteActivationApproved: '카드사 Route 개통 승인',
  CardRouteActivationSuspended: '카드사 Route 개통 정지',
  CardRouteActivationResumed: '카드사 Route 개통 재개',
  CardRouteActivationTerminated: '카드사 Route 개통 종료',
  TransactionLimitPolicyRegistered: '거래 한도 등록',
  TransactionLimitPolicyEnded: '거래 한도 종료',
  PolicyAssigned: '정책 배정',
  PolicyAssignmentEnded: '정책 배정 종료',
  PolicyVersionRegistered: '정책 버전 등록',
}

/**
 * 변경 종류의 한국어 이름. 이 맵에 없는 종류(서버에 새로 생겼는데 화면이 아직 모르는 값)는 원래 코드를 그대로 보인다 — 무엇이 바뀌었는지 숨기지 않는다
 * (대상 열 [historyTargetLabel]과 같은 규칙). 알려진 종류가 모두 이름을 갖는지는 MerchantsScreen.iaB1 시험이 본다.
 */
export function changeTypeLabel(code: string): string {
  return Object.prototype.hasOwnProperty.call(MERCHANT_CHANGE_TYPE_LABELS, code) ? MERCHANT_CHANGE_TYPE_LABELS[code]! : code
}
