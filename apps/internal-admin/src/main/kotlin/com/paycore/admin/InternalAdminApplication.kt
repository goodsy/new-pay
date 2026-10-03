package com.paycore.admin

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration

/**
 * internal-admin 실행 단위. DB를 소유하지 않고 권한 있는 화면에서 API/Application에 위임한다.
 *
 * UserDetailsServiceAutoConfiguration 제외: 인증 방식이 정해지기 전까지 Boot가 기본 사용자("user" + 기동 시 생성 비밀번호)를
 * 만들고 로그에 출력하지 않게 한다. 그 결과 현재는 어떤 요청도 인증될 수 없다(기본 거부).
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class InternalAdminApplication

fun main(args: Array<String>) {
    runApplication<InternalAdminApplication>(*args)
}
