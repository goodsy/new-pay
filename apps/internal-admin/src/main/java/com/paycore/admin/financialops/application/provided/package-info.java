/**
 * financialops 모듈의 공개 API(ACQ-VIEW-001 §4.6): 화면용 매입 현황 조회(목록·파일 상세·레코드 쪽)와 그 입력/출력 타입. 다른 모듈은 이 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3).
 * 의존 방향 financialops → account.provided·audit.provided(순환 없음). PAYMENT·MASTER 운영 모듈(paymentops·merchantops)과 서로 의존하지 않는다(키·permit·장애 격리).
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.financialops.application.provided;
