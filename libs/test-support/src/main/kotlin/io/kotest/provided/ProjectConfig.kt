package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

/**
 * Kotest가 고정 이름(io.kotest.provided.ProjectConfig)으로 찾는 프로젝트 설정. 테스트 classpath의 이 라이브러리에서 로드된다
 * (app마다 복사하지 않는다 — 각 app의 TestInfrastructureSpec이 로드 위치를 확인한다).
 * Spring 생성자 주입을 쓰는 spec에 SpringExtension을 적용한다.
 */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
}
