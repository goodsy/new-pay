package com.paycore.master

import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.SpringApplication
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import kotlin.system.exitProcess

/**
 * master-api 실행 단위. MASTER DB만 소유한다(D-CPC-006). PAYMENT/FINANCIAL Credential을 주입하지 않는다.
 * 하위 패키지가 Spring Modulith application module이 된다. 업무 모듈(Business·Merchant·MID 등)은 MASTER-DOMAIN-001부터 추가한다.
 */
@SpringBootApplication
class MasterApiApplication

/**
 * 웹 프로세스(기본) 또는 DEMO 전용 1회성 명령 모드(`--paycore.master.command=demo-seed` — DEMO-SEED-001 명세 §4.6). 명령 모드는 비웹·주기 job 없이 돌고
 * 종료 코드로 끝난다. 배치 모드(DEMO) 게이트는 컨텍스트 생성 전(merchant 모듈의 환경 후처리기)이다. 진입점은 다른 모듈 타입을 참조하지 않는다(Modulith).
 */
fun main(args: Array<String>) {
    if (!isCommandMode(args)) {
        runApplication<MasterApiApplication>(*args)
        return
    }
    exitProcess(runCommand(args))
}

/** 명령 모드 인자가 있는가(`--paycore.master.command=…`). */
fun isCommandMode(args: Array<String>): Boolean = args.any { it.startsWith(COMMAND_ARG) }

/** 실제 기동 인자: 명령 모드면 비웹·권리 relay/lease job 끔을 **앞에** 덧붙인다(반대 값을 함께 주면 같은 키가 둘이 되어 바인딩 실패 — fail-closed). */
fun effectiveArguments(args: Array<String>): Array<String> = if (isCommandMode(args)) arrayOf(NON_WEB, NO_ENTITLEMENT_JOBS) + args else args

/**
 * 명령 모드 기동·실행 → 종료 코드. 환경 단계 게이트의 거부는 컨텍스트가 없어 빈 기반 mapper가 없으므로 원인 사슬의 [ExitCodeGenerator] 예외로 매핑한다
 * (DEMO 아님 = 3, stderr 고정 한 줄 — logger 초기화 전). 그 밖의 기동 실패는 1.
 */
fun runCommand(args: Array<String>): Int = try {
    SpringApplication.exit(SpringApplication(MasterApiApplication::class.java).run(*effectiveArguments(args)))
} catch (e: RuntimeException) {
    val code = exitCodeOf(e)
    if (code == MODE_REFUSED) System.err.println("demo command refused: DEMO_MODE_REQUIRED")
    code
}

/** 원인 사슬에서 [ExitCodeGenerator] 예외의 코드, 없으면 1. */
fun exitCodeOf(failure: Throwable): Int {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is ExitCodeGenerator) return current.exitCode
        current = current.cause.takeIf { it !== current }
        depth++
    }
    return FAILED
}

private const val COMMAND_ARG = "--paycore.master.command="
private const val NON_WEB = "--spring.main.web-application-type=none"
private const val NO_ENTITLEMENT_JOBS = "--paycore.master.entitlement.scheduling-enabled=false"
private const val MAX_CAUSE_DEPTH = 20
private const val FAILED = 1
private const val MODE_REFUSED = 3
