/**
 * audit 모듈의 공개 API(감사 기록·감사 조회와 입력/출력 타입). 다른 모듈은 이 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3).
 * Kotlin에는 패키지 annotation이 없어 javac가 컴파일하는 src/main/java에 둔다(master-api와 같은 방식).
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.audit.application.provided;
