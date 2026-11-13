/**
 * 화면이 다루는 결제수단·서비스 종류 목록(ADMIN-UI-MERCHANT-002 — D-ADMIN-UI-004 ⑤ "구조만 확장 가능하게").
 * 화면은 고정 문구 대신 이 목록을 그린다. **지금은 서버가 받는 값 그대로 하나씩뿐**이다 — 항목을 더하는 것은 새 결제수단 정책 채택이며
 * 서버 계약(MERCHANT-OPS-002 B1·B3-1 — paymentMethod 'CARD', serviceKind 'CARD_PG_PAYMENT')과 사용자 결정 없이 넣지 않는다.
 *
 * **둘째 수단을 넣기 전 필수 정리(리뷰 MINOR-2)**:
 * 1. `MerchantActivationArea`의 개통 등록 one-shot(submissionId·결과 불명 상태)을 수단별로 나눈다 — 지금은 하나를 공유하고, 결과 불명 동안 다른 수단 버튼을 막는 것으로만 버틴다.
 * 2. `activationChecklist`의 ③ 수단 개통·④ 적용 확인 판정이 'CARD'로 고정되어 있다 — 수단별 점검 규칙(정책)을 정한 뒤 바꾼다.
 */

/** 개통 등록할 수 있는 결제수단(B3-1 `registerPaymentMethodActivation` 의 paymentMethod). */
export const PAYMENT_METHODS: readonly { code: 'CARD'; label: string }[] = Object.freeze([{ code: 'CARD', label: '카드' }])

/** 서비스 계약 종류(B1-2 `addServiceAgreement` 의 serviceKind). */
export const SERVICE_KINDS: readonly { code: 'CARD_PG_PAYMENT'; label: string }[] = Object.freeze([{ code: 'CARD_PG_PAYMENT', label: '카드 PG 결제' }])

export type PaymentMethodCode = (typeof PAYMENT_METHODS)[number]['code']
export type ServiceKindCode = (typeof SERVICE_KINDS)[number]['code']

export const serviceKindLabel = (code: string) => SERVICE_KINDS.find((kind) => kind.code === code)?.label ?? code
