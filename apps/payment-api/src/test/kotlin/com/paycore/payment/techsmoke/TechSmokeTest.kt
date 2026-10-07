package com.paycore.payment.techsmoke

import com.paycore.payment.payment.application.EligibilityTestConfig
import com.paycore.testsupport.MySqlTestContainerConfig
import com.paycore.techsmoke.payment.TechSmokeItemEntity
import org.springframework.boot.autoconfigure.AutoConfigurationPackage
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestPropertySource

/**
 * TECH-001 smoke spec 공통 설정.
 * - main migration 위치에 test-only migration 위치(tech_smoke_*)를 더한다. main application.yml은 db/migration/payment만 가진다
 *   (PaymentApiContextSmokeSpec이 확인).
 * - smoke Entity/Repository는 애플리케이션 기본 패키지(com.paycore.payment) 밖(com.paycore.techsmoke.payment)에 두고
 *   이 설정을 쓰는 컨텍스트에서만 JPA 스캔 대상으로 추가한다. 기본 패키지 안에 두면 test-only 테이블이 없는 다른 컨텍스트에서도
 *   스캔되어 ddl-auto=validate가 실패한다.
 * 같은 설정을 쓰는 spec은 Spring 테스트 컨텍스트(와 MySQL 컨테이너)를 공유한다.
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@SpringBootTest
@Import(MySqlTestContainerConfig::class, TechSmokeJpaPackageConfig::class, EligibilityTestConfig::class)
@TestPropertySource(properties = [TECH_SMOKE_FLYWAY_LOCATIONS])
annotation class TechSmokeTest

const val TECH_SMOKE_FLYWAY_LOCATIONS =
    "spring.flyway.locations=classpath:db/migration/payment,classpath:db/testmigration/payment"

/** Boot의 Entity 스캔과 Spring Data repository 자동 구성이 쓰는 패키지 목록에 smoke 패키지를 추가한다(기본 패키지는 유지). */
@TestConfiguration(proxyBeanMethods = false)
@AutoConfigurationPackage(basePackageClasses = [TechSmokeItemEntity::class])
class TechSmokeJpaPackageConfig
