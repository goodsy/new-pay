package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

/** Kotest가 자동 탐지하는 프로젝트 설정. Spring 생성자 주입을 사용하는 spec에 SpringExtension을 적용한다. */
class ProjectConfig : AbstractProjectConfig() {
    override val extensions = listOf(SpringExtension())
}
