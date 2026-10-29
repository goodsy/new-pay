package com.paycore.archfixture.operatorcancel

/** ArchUnit 위반 fixture(§4.8): provided interface 대신 구체 운영자 서비스에 의존한다. */
class ConcreteServiceCaller(private val service: com.paycore.payment.payment.application.OperatorCancelMalformedAuditService) {
    fun name(): String = service.javaClass.simpleName
}
