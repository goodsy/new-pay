// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
// 메시징 client(kafka/amqp/jms 등) 대역: application 허용 목록 밖의 임의 외부 패키지.
package com.example.messaging

class MessageProducer {
    val sent = mutableListOf<String>()

    fun send(topic: String, payload: String) {
        sent += "$topic:$payload"
    }
}
