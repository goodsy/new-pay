/**
 * merchantops 모듈의 공개 API(MERCHANT-OPS-001 §4): 화면용 가맹점 조회(목록·상세·이력)와 그 입력/출력 타입. 다른 모듈은 이 패키지만 쓸 수 있다(ARCHITECTURE-CONVENTIONS §3).
 * 의존 방향 merchantops → account.provided·audit.provided(순환 없음). merchantops는 PAYMENT 운영 모듈(paymentops)을 모른다. TXN-LIST-ENRICH-001: paymentops가 이 패키지의
 * 라벨 client(`MerchantLabelClient`·`MerchantLabelResult`·`MerchantLabel`·`MerchantLabelFailure`)만 쓴다(한 방향 — 키·permit·장애 격리는 라벨 전용 permit·짧은 timeout으로 유지, ArchUnit).
 */
@org.springframework.modulith.NamedInterface("provided")
package com.paycore.admin.merchantops.application.provided;
