/**
 * paymentops 모듈의 공개 API(ADMIN-PAYMENT-OPS-001 §4.1): PAYMENT 내부 API 클라이언트·계약 계산(oct1 지문·실행 본문)·실행 시간 정책·화면 대상 조회·화면 거래 조회(TXN-OPS-001)와 그 입력/출력 타입.
 * 다른 모듈은 이 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3). 의존 방향 approval → paymentops.provided, paymentops → account.provided·audit.provided(순환 없음 —
 * audit.provided는 TXN-OPS-001 거래 상세 조회 감사 `PAYMENT_VIEWED`).
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.paymentops.application.provided;
