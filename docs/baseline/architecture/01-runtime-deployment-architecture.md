# 01. Runtime / Deployment Architecture

## 목적

승인 지연과 가용성을 보호하기 위해 실행 프로세스, DB, 네트워크, 자원 및 배포 경계를 분리한다.

## 범위

독립 실행 애플리케이션, 자원 격리, 네트워크 접근, HA, 개발·운영 환경 차이를 다룬다.

## 핵심 규칙

| 실행 단위 | 책임 | 허용 DB |
|---|---|---|
| master-api | 온보딩·계약·정책·Credential 메타데이터 | MASTER |
| payment-api | 승인·취소·환불 요청·기관 승인 연동 | PAYMENT |
| payment-relay | 승인 이벤트 전달 | PAYMENT |
| financial-worker | Inbox·후속 금융처리·투영 | FINANCIAL |
| financial-batch | 매입·정산·대사·지급 작업의 실행 조정 | FINANCIAL |
| financial-api | 금융 조회·통제된 금융 명령 | FINANCIAL |
| payment-window | 결제 사용자 화면 | 없음 |
| merchant-api | 가맹점 운영 API의 조합·위임 | 없음 |
| internal-admin / merchant-admin | 권한 있는 화면·API 위임 | 없음 |

- 화면·조합 계층은 각 소유 플랫폼의 API를 호출한다. 관리 조회의 일부 장애가 승인 경로로 전파되지 않게 한다.
- 프로세스마다 서비스 계정, DB 계정, Connection Pool, 스레드 풀, CPU/메모리 한도를 분리한다. PAYMENT 배포에는 FINANCIAL Credential을 주입하지 않는다.
- 운영 DB의 컴퓨팅·스토리지 I/O를 격리한다. Batch 동시성·쿼리 시간·풀 크기를 제한하고 Worker·Payout 실행 자원도 금융 내부에서 보호한다.
- 공통 브로커·네트워크·노드의 장애 가능성을 명시한다. 승인 응답은 브로커 가용성에 의존하지 않으며 Relay 자원 사용은 제한한다.
- 운영 승인 인스턴스는 여러 장애 영역에 배치하고 단일 프로세스 장애를 견디게 한다. DB HA·백업은 별도 검증한다.
- Readiness는 해당 서비스가 안전하게 처리 가능한지 판정한다. Financial 장애나 브로커 장애만으로 Payment를 일괄 비정상 처리하지 않는다.
- Graceful shutdown으로 신규 요청을 차단하고 진행 중 Attempt·DB 커밋·소비 작업을 정리한다. 정리되지 않은 기관 호출은 불명확 상태로 복구한다.
- Docker Compose의 단일 호스트 구성이 운영 HA·물리 격리를 증명하지 않는다. Kubernetes 사용 자체도 필수 전제가 아니다.

## 예시 흐름

```text
정산 Batch CPU 포화
 → Financial 작업 속도 제한 / 이벤트 적체 탐지
 → Payment API는 전용 자원과 PAYMENT 로컬 설정으로 승인 지속
 → Relay는 내구성 있는 Outbox를 보존
 → Financial 복구 후 순서·멱등성 검증하며 따라잡기
```

PAYMENT 저장 용량이 임계치에 가까워지면 신규 거래 제한을 단계적으로 적용한다. 격리는 무한한 적체 수용을 의미하지 않는다.

## 금지사항

- 승인과 정산 Batch를 동일 프로세스·무제한 공유 풀로 실행하는 것.
- 승인 요청마다 MASTER나 FINANCIAL을 필수 동기 조회하는 것.
- 관리자 화면에 전체 DB 접근권한을 부여하는 것.
- 재배포로 진행 중 지급·승인 요청의 결과를 잃는 것.

## 구현 체크리스트

- [ ] 애플리케이션별 허용 연결·자원 한도·네트워크 정책을 정의했다.
- [ ] Financial 중단·부하 상황에서 승인 성능과 오류율을 측정했다.
- [ ] 공통 인프라 장애와 Outbox 용량 한계의 대응을 검증했다.
- [ ] 기동·Readiness·종료 절차를 검증했다.
- [ ] 환경별 구성·비밀정보·배포 버전을 분리했다.
