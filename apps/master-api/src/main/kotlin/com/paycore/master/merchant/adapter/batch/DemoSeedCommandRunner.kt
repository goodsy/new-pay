package com.paycore.master.merchant.adapter.batch

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
 * 명령 모드 runner(`paycore.master.command`가 있을 때만). 1차 방어는 환경 단계 게이트([DemoCommandModeGate])와 웹 컨텍스트 가드([OneShotModeGuard])이고,
 * 여기서는 같은 판정을 **다시** 한다(중복 방어 — 앞 단계가 무력화돼도 DEMO가 아니면 업무 명령에 닿지 않는다). exit code는 [getExitCode].
 */
@Component
@ConditionalOnProperty(prefix = "paycore.master", name = ["command"])
class DemoSeedCommandRunner(
    private val context: ApplicationContext,
    private val environment: Environment,
    private val commandLine: DemoSeedCommandLine,
) : ApplicationRunner, ExitCodeGenerator {

    @Volatile
    private var code: Int = DemoSeedExit.FAILED.code

    override fun run(args: ApplicationArguments) {
        code = when {
            context is WebApplicationContext -> refused("web-context")
            !DemoCommandModeGate.isDemo(environment) -> refused(DemoModeRefusedException.CODE)
            else -> commandLine.run(
                environment.getProperty(DemoCommandModeGate.COMMAND_PROPERTY),
                args.optionNames.filter { !it.contains('.') }.associateWith { name -> args.getOptionValues(name)?.singleOrNull().orEmpty() },
            )
        }
    }

    override fun getExitCode(): Int = code

    private fun refused(reason: String): Int {
        log.warn("operation=demo-seed result=MODE_REFUSED reason={}", reason)
        return DemoSeedExit.MODE_REFUSED.code
    }

    private companion object {
        val log = LoggerFactory.getLogger(DemoSeedCommandRunner::class.java)
    }
}
