// 아키텍처 규칙 meta-test fixture(테스트 소스 전용, 업무 의미 없음). PayCoreArchitectureRulesSpec 참고.
package com.paycore.archfixture.appports.badjavanet.sample.application

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest

/** 외부 호출을 required Port 없이 application에서 직접 수행(실제 호출은 하지 않는 fixture). */
class DirectHttpService(private val client: HttpClient) {
    fun request(id: String): HttpRequest = HttpRequest.newBuilder(URI.create("https://example.invalid/" + id)).build()

    fun version(): HttpClient.Version = client.version()
}
