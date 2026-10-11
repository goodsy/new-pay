/**
 * merchant 모듈의 공개 API(관리 명령 use case·조회 use case와 입력/출력 타입). 다른 모듈은 이 패키지만 쓸 수 있다
 * (ARCHITECTURE-CONVENTIONS §3 모듈 공개 범위). Kotlin에는 패키지 annotation이 없어 javac가 컴파일하는 src/main/java에 둔다.
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.master.merchant.application.provided;
