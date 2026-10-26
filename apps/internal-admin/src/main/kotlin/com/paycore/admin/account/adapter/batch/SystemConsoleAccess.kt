package com.paycore.admin.account.adapter.batch

import org.springframework.stereotype.Component
import java.io.Writer

/** 실제 콘솔: `System.console() != null && isTerminal()`일 때만 writer(§17.8). */
@Component
class SystemConsoleAccess : ConsoleAccess {
    override fun terminalWriter(): Writer? = System.console()?.takeIf { it.isTerminal }?.writer()
}
