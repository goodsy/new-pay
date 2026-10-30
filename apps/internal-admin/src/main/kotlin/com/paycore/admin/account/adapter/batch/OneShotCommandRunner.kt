package com.paycore.admin.account.adapter.batch

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.ExitCodeGenerator
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationContext
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.context.WebApplicationContext

/**
 * 1회성 명령 모드(§4.9 — `--paycore.admin.command=bootstrap|recovery`가 있을 때만 등록). **모드 가드**: 웹 애플리케이션 컨텍스트(웹 서버 구성)거나 스케줄러
 * (승인 만료 job)가 켜져 있으면 DB에 닿기 전에 거부한다 — 웹 프로필로 기동한 프로세스는 명령 인자를 거부하고, 명령 모드는 비웹·스케줄러 없이만 돈다.
 * 진입점(`main`)은 명령 모드에서 `spring.main.web-application-type=none`·스케줄러 끔을 앞에 덧붙이고 이 runner의 종료 코드로 끝낸다.
 * (ADMIN-BOOT-002 I-12) 명령 속성이 웹 컨텍스트에 닿으면 [OneShotModePeriodicJobGuard]가 빈 정의 단계에서 기동을 실패시키므로 아래 `web-context` 거부는
 * 그 앞 단계가 무력화됐을 때만 닿는 중복 방어다.
 */
@Component
@ConditionalOnProperty(prefix = "paycore.admin", name = ["command"])
class OneShotCommandRunner(
    private val context: ApplicationContext,
    private val environment: Environment,
    private val commandLine: OneShotCommandLine,
) : ApplicationRunner, ExitCodeGenerator {

    @Volatile
    private var code: Int = OneShotExit.FAILED.code

    override fun run(args: ApplicationArguments) {
        code = when {
            context is WebApplicationContext -> refused("web-context")
            environment.getProperty("paycore.admin.approval.expiry-job.enabled", Boolean::class.java, true) -> refused("scheduler-enabled")
            else -> commandLine.run(
                environment.getProperty("paycore.admin.command"),
                args.optionNames.filter { !it.contains('.') }.associateWith { name -> args.getOptionValues(name)?.singleOrNull().orEmpty() },
            )
        }
    }

    override fun getExitCode(): Int = code

    /** 모드 거부 한 줄(S6 리뷰 nit-1 — 예: `PAYCORE_ADMIN_COMMAND` 환경변수가 웹 프로세스에 닿은 경우). 인자 값·코드는 남기지 않는다. */
    private fun refused(reason: String): Int {
        log.warn("operation=one-shot result=mode-refused reason={}", reason)
        return OneShotExit.MODE_REFUSED.code
    }

    private companion object {
        val log: Logger = LoggerFactory.getLogger(OneShotCommandRunner::class.java)
    }
}
