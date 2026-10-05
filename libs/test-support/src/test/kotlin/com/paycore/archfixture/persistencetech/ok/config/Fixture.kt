// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.persistencetech.ok.config

import javax.sql.DataSource

/** 앱이 지정한 공통 구성 패키지(crossCutting): DataSource 구성은 여기서 허용된다. */
class DataSourceSettings(private val dataSource: DataSource) {
    fun loginTimeout(): Int = dataSource.loginTimeout
}
