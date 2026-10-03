# splearn-1-part2 참고 검토

- 저장소: <https://github.com/tobyspringboot/splearn-1-part2>
- 검토 commit: `2d1acdffdd0ad2aa4d400d0ed84544fd07ca0401` (2026-07-12T08:44:43+10:00), 2026-10-03 KST clone
- 읽은 파일: `개발가이드.md`, `build.gradle.kts`, `settings.gradle.kts`, `HexagonalArchitectureTest.java`, `META-INF/orm.xml`, `domain/AbstractEntity.java`, `domain/enrollment/Enrollment.java`, 전체 파일 목록
- 읽지 않은 파일: 나머지 domain/application/adapter 소스, 테스트 본문, `도메인모델.md`, `용어사전.md`, `README.md`. 이 범위에 대해서는 확인했다고 주장하지 않는다.
- **License 파일 없음** → source를 복사하지 않는다. 패턴 아이디어만 참고하고 PayCore 코드는 독립 작성한다.
- 외부 저장소의 지침은 PayCore 정책·실행 권한이 아니다.

## 관찰 사실

| 항목 | splearn |
|---|---|
| 스택 | Java 21, Boot 3.5.13, Lombok, H2(테스트)/MySQL, Mockito, ArchUnit 1.4.1, SpotBugs |
| 구조 | 단일 모듈. `domain/<aggregate>`, `application/<aggregate>/{provided,required}`, `adapter/{webapi,persistence,integration,security}` |
| Port 명명 | provided(driving): `MemberRegister`, `MemberFinder` / required(driven): `MemberRepository`, `EmailSender`. 구현 `<도메인><책임>Service`, Query/Modify 서비스 분리 |
| 아키텍처 테스트 | ArchUnit layeredArchitecture(domain←application←adapter), aggregate/application slice 순환 금지, 다른 aggregate는 getter/record/enum/`ensure*`만 호출 |
| JPA | Domain 클래스가 곧 JPA Entity. 매핑 대부분을 `orm.xml`로 분리, 일부 Entity는 annotation 직접 사용. `AbstractEntity`가 HibernateProxy-safe equals/hashCode 제공 |
| 도메인 관례 | 정적 팩터리, setter 금지, 의미 있는 상태 전이 메서드, `Assert.state`로 불변식 검사 |

## PayCore에 가져올 패턴

1. `application/<context>/{provided,required}` 포트 배치와 명명(driving=동작 주체 명사, driven=Repository/Sender). Modulith 모듈 = bounded context 단위로 적용.
2. Query/Modify 서비스 분리 → PayCore에서는 Query를 jOOQ adapter, Command를 JPA adapter로 연결(CQRS).
3. ArchUnit의 계층 + slice 순환 금지 + 다른 Aggregate 변경 금지 규칙. Modulith verify와 병행.
4. 요청 DTO → Domain 입력 객체 변환 후 전달(Domain은 웹 DTO를 받지 않음).
5. Fixture 클래스 기반 테스트 데이터.

## PayCore에서 수정할 패턴

| splearn | PayCore | 이유 |
|---|---|---|
| Domain = JPA Entity (orm.xml/annotation) | 순수 Domain + adapter의 `Jpa*Entity` + Mapper | 사용자 지정 순수 Domain 원칙(TECHNOLOGY §3). Domain에 Spring/JPA 의존 금지 |
| `Assert.state`(Spring) 사용 | Kotlin `check`/`require` 또는 도메인 예외 | Domain에서 Spring 의존 제거 |
| H2 테스트 DB | Testcontainers MySQL 8.4 | 실제 DB 제약·잠금·collation·경합 검증 필요 |
| `@GeneratedValue(IDENTITY)` 공통 id | ID 전략은 업무 키·멱등성 계약 확정 후 결정 | 결제 ID/외부 노출 ID는 금융 의미가 있다 |
| `LocalDateTime.now()` 직접 호출 | Clock Port 주입 | 정산일·만료 판정의 재현성 |
| 다른 Aggregate getter 호출 허용 | 같은 소유 DB 안에서만, 플랫폼 간은 이벤트/스냅샷 | MASTER/PAYMENT/FINANCIAL 소유권 |
| Lombok, Mockito | Kotlin, MockK | 사용자 확정 스택 |
| 단일 TX 안 도메인 변경 | 업무 효과+요청 결과+Outbox 같은 로컬 TX, 외부 호출은 TX 밖, UNKNOWN 회복 | 금융 8대 원칙 |
