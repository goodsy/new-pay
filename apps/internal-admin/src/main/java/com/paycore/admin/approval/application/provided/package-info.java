/**
 * approval 모듈의 공개 API(2인 승인 요청·승인·거절·철회·만료 정리, 승인 조회와 입력/출력 타입). 다른 모듈은 이 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3).
 * account는 approval에 의존하지 않는다(의존 방향 approval → account·audit).
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.approval.application.provided;
