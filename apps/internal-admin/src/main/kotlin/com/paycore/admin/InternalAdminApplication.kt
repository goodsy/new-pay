package com.paycore.admin

import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import kotlin.system.exitProcess

/**
 * internal-admin 실행 단위. DB를 소유하지 않고 권한 있는 화면에서 API/Application에 위임한다.
 *
 * UserDetailsServiceAutoConfiguration 제외: 인증 방식이 정해지기 전까지 Boot가 기본 사용자("user" + 기동 시 생성 비밀번호)를
 * 만들고 로그에 출력하지 않게 한다. 그 결과 현재는 어떤 요청도 인증될 수 없다(기본 거부).
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
class InternalAdminApplication

/**
 * 1회성 명령 모드(§4.9 — `--paycore.admin.command=…`): 비웹·스케줄러 끔을 **인자 앞에** 덧붙여 강제한다(운영자가 반대 값을 함께 주면 같은 키가 둘이 되어 기동이
 * 실패하거나 runner의 모드 가드가 거부한다 — fail-closed). 명령이 끝나면 runner의 종료 코드로 프로세스를 끝낸다.
 */
fun main(args: Array<String>) {
    val context = runApplication<InternalAdminApplication>(*effectiveArguments(args))
    if (isCommandMode(args)) exitProcess(SpringApplication.exit(context))
}

/** 명령 모드 인자가 있는가(`--paycore.admin.command=…`). */
fun isCommandMode(args: Array<String>): Boolean = args.any { it.startsWith(COMMAND_ARG) }

/** 실제 기동 인자(순수 함수 — S6 리뷰 nit-1): 명령 모드면 비웹·스케줄러 끔을 앞에, 아니면 그대로. */
fun effectiveArguments(args: Array<String>): Array<String> = if (isCommandMode(args)) arrayOf(NON_WEB, NO_SCHEDULER) + args else args

private const val COMMAND_ARG = "--paycore.admin.command="
private const val NON_WEB = "--spring.main.web-application-type=none"
private const val NO_SCHEDULER = "--paycore.admin.approval.expiry-job.enabled=false"
