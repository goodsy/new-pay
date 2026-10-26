package com.paycore.buildlogic.frontend

import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.MapProperty
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * 개발자용 lockfile 재생성(check에 들어가지 않음, 네트워크 필요). Coordinator Q-S0-2 판정 A의 **2단계 절차**를 고정한다.
 *
 * 1. `overrides`를 뺀 package.json으로 임시 디렉터리에서 `npm install --package-lock-only --ignore-scripts --before=<policy.before>`
 *    — 전이 의존까지 14일 규칙(cutoff 이전 게시본)으로 해석한다.
 * 2. 그 lockfile을 프론트에 복사하고, `overrides`(보안 patch 예외)가 있는 실제 package.json으로 `--before` 없이
 *    `npm install --package-lock-only --ignore-scripts` — 기존 lockfile을 유지한 채 override 대상만 바뀐다.
 * (`--before`와 override를 한 번에 쓰면 cutoff 이후 버전을 해석하지 못해 ETARGET — S0 실측.)
 * 실행 환경은 [FrontendNpmEnvironment] 격리 환경이다.
 */
@UntrackedTask(because = "regenerates source files on explicit request")
abstract class FrontendLockfileRegenerateTask : DefaultTask() {

    @get:Inject abstract val execOperations: ExecOperations

    @get:Internal abstract val frontendDir: DirectoryProperty
    @get:Internal abstract val workDir: DirectoryProperty
    @get:Internal abstract val nodeExecutable: RegularFileProperty
    @get:Internal abstract val npmCli: RegularFileProperty
    @get:Internal abstract val environment: MapProperty<String, String>

    @TaskAction
    fun regenerate() {
        val frontend = frontendDir.get().asFile
        val work = workDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        @Suppress("UNCHECKED_CAST")
        val policy = JsonSlurper().parse(frontend.resolve("lockfile-policy.json")) as Map<String, Any?>
        val before = policy["before"] as String
        @Suppress("UNCHECKED_CAST")
        val packageJson = (JsonSlurper().parse(frontend.resolve("package.json")) as Map<String, Any?>).toMutableMap()
        packageJson.remove("overrides")
        work.resolve("package.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(packageJson)) + "\n")
        frontend.resolve(".npmrc").copyTo(work.resolve(".npmrc"), overwrite = true)

        npm(work, "install", "--package-lock-only", "--ignore-scripts", "--before=$before")
        work.resolve("package-lock.json").copyTo(frontend.resolve("package-lock.json"), overwrite = true)
        npm(frontend, "install", "--package-lock-only", "--ignore-scripts")
        logger.lifecycle("package-lock.json regenerated (before=$before, then security overrides applied)")
    }

    private fun npm(dir: java.io.File, vararg args: String) {
        execOperations.exec {
            workingDir = dir
            executable = nodeExecutable.get().asFile.path
            args(npmCli.get().asFile.path, *args)
            setEnvironment(this@FrontendLockfileRegenerateTask.environment.get())
        }
    }
}
