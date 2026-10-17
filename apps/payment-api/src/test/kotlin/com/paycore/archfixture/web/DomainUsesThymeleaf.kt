package com.paycore.archfixture.web

import org.thymeleaf.context.Context

/** ArchUnit 위반 fixture(시험 전용, 실행하지 않는다): web adapter 밖 클래스가 Thymeleaf에 의존한다. */
class DomainUsesThymeleaf {
    fun render(): Context = Context()
}
