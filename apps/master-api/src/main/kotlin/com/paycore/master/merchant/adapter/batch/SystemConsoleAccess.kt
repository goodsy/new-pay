package com.paycore.master.merchant.adapter.batch

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.io.Writer

/** JDK 25: 표준 스트림이 하나라도 리다이렉트되면 `System.console()`이 null이거나 터미널이 아니다(internal-admin S0 실측과 같은 판정). */
/** 명령 프로세스에만 빈이 있다(DEMO-SEED-001 리뷰 m-1 — 웹 프로세스에는 명령 관련 빈이 없다). */
@Component
@ConditionalOnProperty(prefix = "paycore.master", name = ["command"])
class SystemConsoleAccess : ConsoleAccess {
    override fun terminalWriter(): Writer? = System.console()?.takeIf { it.isTerminal }?.writer()
}
