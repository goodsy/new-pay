// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.bootapp.badnestedonly.sample.application

import org.springframework.boot.autoconfigure.SpringBootApplication

/** 유일한 @SpringBootApplication이 base root가 아닌 모듈 계층에 있다(개수 조건은 통과, 위치 조건만 위반). */
@SpringBootApplication
class MisplacedApplication
