package com.paycore.payment.payment.adapter.persistence

import com.paycore.payment.PaymentMySqlContainerConfig
import com.paycore.payment.payment.application.EligibilityTestConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * CP-DB-001 실제 MySQL 8.4 시험 공통 설정(같은 설정의 spec은 Spring 컨텍스트와 컨테이너 하나를 공유한다).
 *
 * - 운영 migration(db/migration/payment)과 운영 runtime 권한 migration(db/runtime-privilege/payment)을 적용한다(TECH-001 smoke 테이블 없음).
 *   여기에 시험 전용 probe(db/testprobe/payment: Flyway 세션의 time_zone·접속 계정 기록, runtime 권한 없음 — root로만 읽는다)를 더한다.
 * - DB 연결: [PaymentMySqlContainerConfig] — 실제 초기화 스크립트 계정. 앱 pool은 runtime 계정(표 단위 권한), Flyway는 migration 계정.
 * - `innodb_lock_wait_timeout`을 세션마다 8초로 줄인다: 잠금 대기 timeout 분류 시험이 기본 50초를 기다리지 않게 하는 **시험 전용**
 *   값이다(경합 관찰 hook이 대기자를 확인하는 동안 대기자가 먼저 timeout되지 않을 만큼은 길게). 운영 lock wait 값은 API timeout 예산과 함께 CP-API-001에서 정한다(CP-CONTRACT-001 §9 이월 L-4).
 * - 경합 시험이 동시에 여러 연결을 쓰므로 pool을 16으로 늘린다(시험 전용).
 * - [PaymentDbTestConfig]: 장애 주입 hook이 달린 Port 대역(@Primary)과 root 연결 probe.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(PaymentMySqlContainerConfig::class, PaymentDbTestConfig::class, FaultInjectionConfig::class, EligibilityTestConfig::class)
@TestPropertySource(
    properties = [
        "spring.datasource.hikari.connection-init-sql=SET SESSION innodb_lock_wait_timeout = 8",
        "spring.datasource.hikari.maximum-pool-size=16",
        PAYMENT_DB_TEST_FLYWAY_LOCATIONS,
    ],
)
annotation class PaymentDbTest

const val PAYMENT_DB_TEST_FLYWAY_LOCATIONS =
    "spring.flyway.locations=classpath:db/migration/payment,classpath:db/runtime-privilege/payment,classpath:db/testprobe/payment"
