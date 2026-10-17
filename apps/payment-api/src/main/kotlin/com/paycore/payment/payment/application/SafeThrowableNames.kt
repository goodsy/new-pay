package com.paycore.payment.payment.application

/** 결함 로그용 예외 요약: 예외 클래스 이름 사슬과 첫 stack frame만(메시지 원문 없음 — 키·URL·비밀이 들어 있을 수 있다, CP-API n-3·AUTHN-001 §12.6). */
internal object SafeThrowableNames {
    private const val MAX_DEPTH = 10

    fun summary(e: Throwable): String {
        val chain = generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(MAX_DEPTH).joinToString(" <- ") { it.javaClass.name }
        val frame = e.stackTrace.firstOrNull()?.let { " at ${it.className}.${it.methodName}:${it.lineNumber}" }.orEmpty()
        return chain + frame
    }
}
