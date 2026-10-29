package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8): 진입 컨트롤러가 아닌 클래스가 형식 오류 감사를 부른다. */
class RecorderCaller(private val recorder: com.paycore.payment.payment.application.provided.OperatorCancelMalformedRecorder) {
    fun call(m: com.paycore.payment.payment.application.provided.MalformedOperatorCommand) = recorder.record(m)
}
