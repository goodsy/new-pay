package com.paycore.payment.techsmoke

import com.paycore.payment.PaymentMySqlContainerConfig
import com.paycore.payment.payment.application.EligibilityTestConfig
import com.paycore.techsmoke.payment.TechSmokeItemEntity
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * TECH-001 smoke spec 공통 설정.
 * - main migration·runtime 권한 위치에 test-only migration 위치(tech_smoke_*)와 그 runtime 권한 위치(db/testprivilege/payment)를 더한다.
 *   권한 migration을 별도 위치에 두는 이유: jOOQ test 생성기(generateJooqTest)는 db/testmigration/payment만 적용하는데 그 컨테이너에는
 *   권한 부여 procedure가 없다(PAYMENT-DBACCOUNT-001, ARCHITECTURE-CONVENTIONS §9).
 * - DB 연결은 [PaymentMySqlContainerConfig](실제 초기화 스크립트 계정, runtime 계정은 표 단위 권한).
 * - smoke Entity/Repository는 애플리케이션 기본 패키지(com.paycore.payment) 밖(com.paycore.techsmoke.payment)에 두고
 *   이 설정을 쓰는 컨텍스트에서만 JPA 스캔 대상으로 추가한다. 기본 패키지 안에 두면 test-only 테이블이 없는 다른 컨텍스트에서도
 *   스캔되어 ddl-auto=validate가 실패한다.
 * 같은 설정을 쓰는 spec은 Spring 테스트 컨텍스트(와 MySQL 컨테이너)를 공유한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(PaymentMySqlContainerConfig::class, TechSmokeJpaPackageConfig::class, EligibilityTestConfig::class)
@TestPropertySource(properties = [TECH_SMOKE_FLYWAY_LOCATIONS])
annotation class TechSmokeTest

const val TECH_SMOKE_FLYWAY_LOCATIONS =
    "spring.flyway.locations=classpath:db/migration/payment,classpath:db/runtime-privilege/payment," +
        "classpath:db/testmigration/payment,classpath:db/testprivilege/payment"

/** Boot의 Entity 스캔과 Spring Data repository 자동 구성이 쓰는 패키지 목록에 smoke 패키지를 추가한다(기본 패키지는 유지). */
@TestConfiguration(proxyBeanMethods = false)
@AutoConfigurationPackage(basePackageClasses = [TechSmokeItemEntity::class])
class TechSmokeJpaPackageConfig
