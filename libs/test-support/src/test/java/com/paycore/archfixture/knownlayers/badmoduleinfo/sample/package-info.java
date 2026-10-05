// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
// 모듈 root package-info에 @ApplicationModule(type = OPEN)을 두어 Modulith 공개 범위를 여는 시도. 모듈 root는 계층이 아니므로
// classesResideInKnownLayers가 거부한다(round3 I-b 특성화 fixture).
@org.springframework.modulith.ApplicationModule(type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.paycore.archfixture.knownlayers.badmoduleinfo.sample;
