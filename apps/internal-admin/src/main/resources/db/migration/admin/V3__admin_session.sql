-- ADMIN-BOOT-001 §4.4.1 Spring Session JDBC 표(F-6 — Spring Session 기본 대문자 이름 SPRING_SESSION은 권한 procedure의 소문자 이름 규칙에 거부된다).
-- 열은 Spring Session 4.1.1 JDBC 표 정의(schema-mysql.sql)와 같다. 속성 표는 Spring Session이 `%TABLE_NAME%_ATTRIBUTES`로 고정하므로
-- S4의 security.session 구성이 질의 5개를 이 소문자 이름으로 교체한다(S0 §17.6 실측). Hibernate·jOOQ 대상이 아니다(jOOQ 생성 제외).
-- 만료 정리는 Spring Session cleanup cron(DELETE)이 한다 — runtime 권한은 이 두 표만 전체 DML(db/runtime-privilege/admin/V4).
CREATE TABLE admin_session (
    PRIMARY_ID            CHAR(36)     NOT NULL,
    SESSION_ID            CHAR(36)     NOT NULL,
    CREATION_TIME         BIGINT       NOT NULL,
    LAST_ACCESS_TIME      BIGINT       NOT NULL,
    MAX_INACTIVE_INTERVAL INT          NOT NULL,
    EXPIRY_TIME           BIGINT       NOT NULL,
    PRINCIPAL_NAME        VARCHAR(100) NULL,
    CONSTRAINT pk_admin_session PRIMARY KEY (PRIMARY_ID),
    CONSTRAINT uk_admin_session_session_id UNIQUE (SESSION_ID),
    INDEX ix_admin_session_expiry (EXPIRY_TIME),
    INDEX ix_admin_session_principal (PRINCIPAL_NAME)
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;

CREATE TABLE admin_session_attributes (
    SESSION_PRIMARY_ID CHAR(36)     NOT NULL,
    ATTRIBUTE_NAME     VARCHAR(200) NOT NULL,
    ATTRIBUTE_BYTES    BLOB         NOT NULL,
    CONSTRAINT pk_admin_session_attributes PRIMARY KEY (SESSION_PRIMARY_ID, ATTRIBUTE_NAME),
    CONSTRAINT fk_admin_session_attributes_session FOREIGN KEY (SESSION_PRIMARY_ID) REFERENCES admin_session (PRIMARY_ID) ON DELETE CASCADE
) ENGINE = InnoDB ROW_FORMAT = DYNAMIC DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_bin;
