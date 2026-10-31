package com.paycore.testsupport

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * 실제 MySQL 8.4 LTS 컨테이너(app마다 자기 소유 DB 역할: payment-api=PAYMENT, financial-batch=FINANCIAL).
 * 운영 DB/키에 연결하지 않는다. 같은 설정을 쓰는 spec은 Spring 테스트 컨텍스트와 컨테이너를 공유한다.
 *
 * 사용하는 app은 spring-boot-testcontainers·testcontainers-mysql을 직접 testImplementation으로 선언해야 한다
 * (이 라이브러리는 compileOnly로만 참조한다).
 */
@TestConfiguration(proxyBeanMethods = false)
class MySqlTestContainerConfig {

    @Bean
    @ServiceConnection
    fun mysqlContainer(): MySQLContainer = newTestMySqlContainer()

    companion object {
        /** MySQL 이미지 tag의 저장소 단일 기준. payment-api jOOQ 생성기도 이 상수를 쓴다. */
        const val MYSQL_IMAGE = "mysql:8.4.11"

        /**
         * 시험 MySQL 서버 기동 옵션(TEST-PERF-001, docs/repository/TEST-PERF-001-GATE-DURATION.md). **충돌(crash) 복구에만** 관계된 설정이다:
         * redo log를 commit마다 fsync하지 않고(초 단위), binlog를 fsync하지 않고, doublewrite buffer를 끈다. 트랜잭션 원자성·rollback·잠금·격리·
         * 제약은 그대로다(서버가 비정상 종료될 때만 차이가 나고, 시험은 MySQL 서버를 죽이지 않는다). binlog 자체는 켜 둔다(`--skip-log-bin` 금지 —
         * 운영처럼 binlog가 켜진 서버의 함수/procedure 생성 제약을 그대로 받는다).
         */
        val TEST_SERVER_OPTIONS: List<String> = listOf("--innodb-flush-log-at-trx-commit=2", "--sync-binlog=0", "--innodb-doublewrite=OFF")

        /**
         * 모든 시험 MySQL 컨테이너의 공통 시작점: 단일 이미지 + [TEST_SERVER_OPTIONS] + 시간대 표 적재 생략(`MYSQL_INITDB_SKIP_TZINFO` — 저장소의
         * SQL·설정은 이름 있는 시간대('Asia/Seoul' 등)를 쓰지 않고 offset('+00:00')과 OS 시간대(SYSTEM)만 쓴다). 측정: 기동 약 16~20초 → 약 7~13초.
         * 매번 새 컨테이너 객체다(재사용·withReuse 없음). 계정·초기화 스크립트·OS 시간대는 각 모듈이 이어서 설정한다.
         */
        fun newTestMySqlContainer(): MySQLContainer = MySQLContainer(DockerImageName.parse(MYSQL_IMAGE))
            .withEnv("MYSQL_INITDB_SKIP_TZINFO", "1")
            .withCommand(*TEST_SERVER_OPTIONS.toTypedArray())
    }
}
