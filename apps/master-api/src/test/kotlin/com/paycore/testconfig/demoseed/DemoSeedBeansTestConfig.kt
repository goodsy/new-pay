package com.paycore.testconfig.demoseed

import com.paycore.master.merchant.adapter.batch.ConsoleAccess
import com.paycore.master.merchant.adapter.batch.DemoSeedCommandLine
import com.paycore.master.merchant.application.DemoMerchantSeedService
import com.paycore.master.merchant.application.provided.DemoMerchantSeedUseCase
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import

/**
 * 시험 전용(DEMO-SEED-001 리뷰 m-1): 운영에서는 seed 조정자·명령 adapter가 `demo-seed` 명령 프로세스에만 있으므로(가드 등록·명령 속성 조건) 일반 MasterDbTest
 * 컨텍스트에는 없다. seed Use Case·명령 adapter를 DB로 시험하는 Spec만 이 구성을 가져와 같은 클래스를 빈으로 만든다(터미널 없음 — TTY 출력은 거부).
 * 이 구성을 쓰는 Spec은 별도 컨텍스트(새 DB)를 공유한다.
 * 패키지는 앱 component scan 기준(`com.paycore.master`) **밖**이다(리뷰 m-2): 시험 소스까지 scan하는 `SpringApplicationBuilder` 명령 컨텍스트(DemoSeedCommandSpec I-3)에
 * 이 구성이 섞이지 않아야 그 시험이 운영 배선(가드의 seed 서비스 등록)만으로 빈이 하나인지 증명한다.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import(DemoMerchantSeedService::class)
class DemoSeedBeansTestConfig {
    @Bean
    fun demoSeedCommandLine(seed: ObjectProvider<DemoMerchantSeedUseCase>): DemoSeedCommandLine = DemoSeedCommandLine(seed, ConsoleAccess { null })
}
