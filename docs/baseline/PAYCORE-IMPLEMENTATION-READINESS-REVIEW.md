# PayCore Implementation Readiness Guide 검토

2026-10-03 KST / 문서 검토 / 코드·DB·테스트 실행 없음

대상: C:/sykim/dev/ai/PAYCORE-VIBE-CODING-IMPLEMENTATION-READINESS-GUIDE.md

## 판정

명세를 먼저 확정하고 구현 세부를 AI에 맡기는 순서는 적절하다. 공통 지침으로 사용할 수 있으나 §21 CreatePayment 시작 명세는 기존 확정 규칙과 누락/충돌이 있어 그대로 READY로 표시할 수 없다. 이 검토는 원문의 개발 명령을 실행한 것이 아니다. 원본은 수정하지 않았고 별도 수정안과 첫 기능 기술 계약 초안을 작성했다.

## 우선 수정 사항

| 중요도 | 위치 | 문제 | 수정 |
|---|---|---|---|
| 높음 | §2/§21 | REQUESTED가 신규 시도 차단 목록에서 빠짐 | REQUESTED도 점유, 같은 내용은 기존 시도 반환 |
| 높음 | §21 | Payment별 version/일반 주문 index만으로 다른 Payment 사이 경쟁을 막지 못함 | PAYMENT의 OrderKey별 영속 control/slot, 생성·해제·승인 시작의 같은 직렬화 규칙 |
| 높음 | §2/§21 | FAILED/EXPIRED면 무조건 재시도처럼 읽힘 | 확정 무효과/안전한 만료 근거를 요구. 단순 timeout/NOT_FOUND는 부족 |
| 높음 | §7/§21 | Site·환경·인증 actor/scope가 API에 없음 | 신뢰된 CommandContext와 Site를 포함, 소유권·Scope·Snapshot 검증 |
| 높음 | §5 | 기관 요청 ID 전역 UNIQUE 예시 | 환경/기관/계약계정/operation namespace로 계약. 실제 기관 규약 확인 |
| 높음 | §4 | 승인 확정 TX 예시에 요청 결과/Outbox 누락 | 원 금융 사실+요약+Attempt/승인+요청 결과+Outbox 동일 로컬 TX |
| 중간 | §15/§16 | 체크 표시만으로 READY/DONE 판정 가능 | 실제 revision/hash·명령·테스트 수·보고서·review 증거. 해당 기능의 미결만 차단 |
| 중간 | §21 | commit 후 응답 유실/다른 키 동일 주문/다른 payload 없음 | 요청키 및 주문 슬롯의 중복 제어를 각각 시험 |
| 중간 | §23 | Domain 끝낸 뒤 Persistence까지 미루는 것으로 해석 가능 | 작은 기능별 실제 DB·동시성·장애 검증까지 완료 후 다음 기능 |
| 중간 | §20 | 첫 기능 변경의 선행조건 불명 | PAYMENT 독립 검증은 허용. MASTER 준비 상태는 명시 fixture, 실제 개통 완료로 표시하지 않음 |

Payment 테이블의 (MID, orderId) UNIQUE는 금지지만 OrderKey control 테이블의 (environment, MID, orderId) UNIQUE는 필요할 수 있다. 전자는 시도 이력 1:N을 깨고 후자는 하나의 주문 조정 행을 보장한다. 서로 다른 제약이다.

## 첫 기능의 의미

CreatePayment는 기관 승인이 아닌 결제 의도 생성이다. 기관 Attempt나 AUTHORIZATION 금융 사실을 만들지 않는다. DB 커밋 응답이 유실됐다고 Payment UNKNOWN으로 바꾸거나 신규 시도를 만들지 않는다. 동일 멱등키로 생성 결과를 확인한다. UNKNOWN 시나리오는 기존 UNKNOWN Payment의 점유 유지 및 향후 AuthorizePayment의 기관 복구 시험과 연결한다.

최초 MID 발급보다 CreatePayment를 먼저 구현할 수는 있다. 이때 유효한 MASTER Snapshot을 명시 fixture로 제공하고 실제 MASTER 개통/권리 전달 검증은 통합 단계에서 수행한다. 금융 규칙을 생략하는 stub를 운영 준비 완료로 간주하지 않는다.

## 생성 산출물

- [수정 지침 v0.2](PAYCORE-VIBE-CODING-IMPLEMENTATION-READINESS-GUIDE-v0.2.md)
- [CreatePayment 기술 계약 초안](PAYCORE-CREATE-PAYMENT-IMPLEMENTATION-CONTRACT-v0.1.md)

두 문서의 새 객체명/키/오류 구조는 기존 사용자 정책을 구현하는 기술 제안이다. 실제 저장소·DDL·API/이벤트 계약·테스트 명령과 검토할 잔여 입력을 확인하기 전 구현 READY로 승격하지 않는다.
