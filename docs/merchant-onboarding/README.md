# 가맹점 온보딩 문서

관리자 콘솔에서 새 가맹점을 등록하고, 계약을 두 사람이 승인하고, 첫 MID를 두 사람이 발급하고, 개통해 결제를 받기까지의 과정을 설명하는 문서 모음이다.
설계 명세(정본)는 [MERCHANT-OPS-002](../repository/MERCHANT-OPS-002-MERCHANT-ONBOARDING.md)이고, 이 폴더는 그 명세와 코드를 읽기 쉽게 풀어 쓴 해설이다. 둘이 다르면 명세와 코드가 우선한다.

| 문서 | 내용 |
|---|---|
| [01-business-flow.md](01-business-flow.md) | 업무 흐름 — 등록부터 결제 가능까지의 단계와 단계별 규칙 |
| [02-service-flow.md](02-service-flow.md) | 서비스 흐름 — internal-admin ↔ master-api 2인 승인 실행, 멱등·회복 |
| [03-system-composition.md](03-system-composition.md) | 시스템 구성 — 앱·DB, 헥사고날 계층, 핵심 테이블, 8대 원칙 증거 |
| [db/00-overview.md](db/00-overview.md) | DB 테이블 명세와 ERD — 다섯 DB(MASTER·PAYMENT·FINANCIAL·ADMIN·Simulator). Demo DB의 실제 스키마(HEAD `1b5538d`, migration 86개 checksum 일치)와 대조함 |

## 현재 범위

| 배치 | 내용 | 상태 |
|---|---|---|
| B1 | 사업자·가맹점·Site 등록, 계약 초안·서비스 계약(CSA) | 완료 (`89c6fac`) |
| B2 | 계약 Revision 개정, 계약 승인(2인), 첫 Default MID 발급(2인) | 완료 (`1b5538d`, 게이트 MOPS-gate5 PASS) |
| B3 | 개통 — 결제수단·Site 적용 확인·Site 심사·통화·카드 Route | 설계 중 |
| B4 | 한도·수수료 | 예정 |
| B5 | 중지·종료(안전 방향 1인) | 예정 |

사업자 기본정보(명세 [BUSINESS-PROFILE-001](../repository/BUSINESS-PROFILE-001-BUSINESS-PROFILE.md)) — 가맹점 등록 ① 앞 단계를 별도 작업으로 확장했다.

| 배치 | 내용 | 상태 |
|---|---|---|
| BIZ-1 | MASTER Domain·DB(V15·V16)·Application — 임시저장/등록 완료, 대표자·업태/종목·주소·담당자, 변경 이력 | 완료 (`2821dc6`) |
| BIZ-2 | MASTER 내부 관리자 API(목록·번호 조회·상세·이력·명령) | 완료 (`5dab2e7`) |
| BIZ-3 | ADMIN API·권한(조회 OPERATOR·APPROVER, 명령 OPERATOR)·감사 | 완료 (`1f8e530`) |
| BIZ-4 | 화면(사업자 조회·사업자 등록 폼·가맹점 등록 ① 번호 조회) + 전환(새 가맹점은 등록 완료 사업자만, 옛 사업자 등록 경로 제거) | 구현 — 게이트·리뷰 대기 |

Demo 값(실행 창·한도·요율 등)은 운영값이 아니다. 실제 기관·운영 DB는 쓰지 않고 가상 기관(Simulator)을 쓴다.
