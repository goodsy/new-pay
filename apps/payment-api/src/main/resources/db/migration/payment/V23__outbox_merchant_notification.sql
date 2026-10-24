-- WEBHOOK-001 명세 §5.0(rev 0.2 — H-3·m-3·m-9)·D-WH-009 V23: Outbox 가맹점 알림 표시 열 두 개 + 비종결 실패 기록 표.
-- 업무 TX는 이 열들을 쓰지 않는다(INSERT 열 목록 밖 — DEFAULT). fan-out만 PK UPDATE로 두 열을 바꾼다(runtime 권한은 그 두 열의 열 단위 UPDATE뿐 — V24).
-- publish_status(FINANCIAL 전달 표시)·payload 등 다른 열은 바꾸지 않는다.

-- m-9 순서 ①: 기존 행은 즉시 SKIPPED_PRE_DEPLOY(배포 전 TEST 결과를 한꺼번에 가맹점에 보내지 않는다 — §18-4 판정). 이 문장 뒤·② 전에 이전 인스턴스가 INSERT하면
-- 그 행도 SKIPPED가 될 수 있다 — 배포 관례(이전 payment-api를 모두 내린 뒤 migration, §20)를 따른다.
ALTER TABLE outbox_event
    ADD COLUMN merchant_notification_status VARCHAR(24) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL DEFAULT 'SKIPPED_PRE_DEPLOY';

-- ②: 새 행은 PENDING(fan-out 대상).
ALTER TABLE outbox_event
    ALTER COLUMN merchant_notification_status SET DEFAULT 'PENDING';

-- ③: 종결 시각·CHECK·인덱스. 종결(NOTIFIED·SUPPRESSED·NOT_APPLICABLE)에만 시각이 있다. FANOUT_FAILED는 비종결(재시도 — 실패 기록은 아래 표).
ALTER TABLE outbox_event
    ADD COLUMN merchant_notified_at DATETIME(3) NULL,
    ADD CONSTRAINT ck_outbox_event_merchant_notification CHECK (
        (merchant_notification_status IN ('NOTIFIED', 'SUPPRESSED', 'NOT_APPLICABLE') AND merchant_notified_at IS NOT NULL)
        OR (merchant_notification_status IN ('PENDING', 'FANOUT_FAILED', 'SKIPPED_PRE_DEPLOY') AND merchant_notified_at IS NULL)),
    ADD INDEX ix_outbox_event_merchant_notification (merchant_notification_status, id);

-- fan-out 비종결 실패 기록(m-3 — D-WH-009가 Outbox에서 바꿀 수 있는 열을 두 개로 제한하므로 실패 세부는 별도 표). Outbox 행당 하나, 실패마다 횟수 +1·코드·재시도 시각 갱신.
-- outbox_event로 가는 FK는 두지 않는다(WH-23 — 부모 행 공유 잠금 회피). 실패 코드는 고정 분류 코드만(payload·예외 문구 없음). 행 삭제 경로 없음(성공으로 종결돼도 실패 이력으로 남는다).
CREATE TABLE merchant_notification_failure (
    outbox_id    BIGINT      NOT NULL,
    failures     INT         NOT NULL,
    failure_code VARCHAR(48) CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin NOT NULL,
    retry_at     DATETIME(3) NOT NULL,
    first_failed_at DATETIME(3) NOT NULL,
    last_failed_at  DATETIME(3) NOT NULL,
    CONSTRAINT pk_merchant_notification_failure PRIMARY KEY (outbox_id),
    CONSTRAINT ck_merchant_notification_failure_count CHECK (failures >= 1),
    CONSTRAINT ck_merchant_notification_failure_code CHECK (REGEXP_LIKE(failure_code, '^[A-Z0-9_]{1,48}$', 'c')),
    INDEX ix_merchant_notification_failure_retry (retry_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
