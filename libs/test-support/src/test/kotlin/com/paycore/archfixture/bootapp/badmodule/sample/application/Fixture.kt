// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.bootapp.badmodule.sample.application

import org.springframework.boot.autoconfigure.SpringBootApplication

/** 모듈 계층 안의 두 번째 @SpringBootApplication(component scan·auto-configuration 경계가 둘로 갈린다). */
@SpringBootApplication
class NestedApplication
