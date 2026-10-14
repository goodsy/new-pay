/**
 * entitlement 모듈의 공개 API(권리 Snapshot 수신 use case·결제 생성 판정 조회·신선도 조회와 입력/출력 타입, MASTER-SNAPSHOT-001). 다른 모듈은 이
 * 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3 모듈 공개 범위). Kotlin에는 패키지 annotation이 없어 javac가 컴파일하는 src/main/java에 둔다.
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.payment.entitlement.application.provided;
