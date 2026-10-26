/**
 * account 모듈의 공개 API(계정 1인 명령·등록·인증 실패 기록과 입력/출력 타입). 다른 모듈(approval·security)은 이 패키지만 쓸 수 있다
 * (ARCHITECTURE-CONVENTIONS §3). 입력/출력은 문자열·enum 이름만 — account domain 타입을 노출하지 않는다.
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.account.application.provided;
