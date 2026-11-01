package com.paycore.master.merchant.adapter.batch

import java.io.Writer

/** 대화형 터미널 접근(시험에서 대역으로 바꾼다). 터미널이 아니면 null. */
fun interface ConsoleAccess {
    fun terminalWriter(): Writer?
}
