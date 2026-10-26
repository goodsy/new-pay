package com.paycore.admin.account.adapter.batch

import java.io.Writer

/** TTY 출력 경로(§4.9 — `--output=tty`는 터미널이 있을 때만). 시험은 대역을 쓴다. */
fun interface ConsoleAccess {
    /** 터미널 콘솔의 writer, 터미널이 아니면 null(표준 스트림 하나라도 리다이렉트되면 JDK 25 `System.console()`은 터미널이 아니다 — §17.8 실측). */
    fun terminalWriter(): Writer?
}
