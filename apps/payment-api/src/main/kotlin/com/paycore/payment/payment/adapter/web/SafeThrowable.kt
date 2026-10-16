package com.paycore.payment.payment.adapter.web

/** 예외 요약(n-3): 클래스 이름 사슬과 각 원인의 첫 stack frame. 메시지 원문은 넣지 않는다(키 값·입력이 들어 있을 수 있다). */
object SafeThrowable {
    private const val MAX_DEPTH = 10

    fun summary(e: Throwable): String =
        generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_DEPTH).joinToString(" <- ") { t ->
            val frame = t.stackTrace.firstOrNull()?.let { "${it.className}.${it.methodName}:${it.lineNumber}" } ?: "?"
            "${t.javaClass.name}@$frame"
        }
}
