package com.paycore.buildlogic.frontend

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * 프론트 공급망 입력 검사(ADMIN-BOOT-001 §4.11.2, 네트워크 없음).
 *
 * - `package-lock.json`: lockfileVersion 3, 설치 entry마다 `resolved`가 공식 registry(`https://registry.npmjs.org/`)이고
 *   `integrity`가 sha512, 루트 entry의 의존 선언이 `package.json`과 같다. 예외는 `inBundle: true` 항목 하나뿐 — 부모 항목이 그 이름을 bundleDependencies로
 *   선언하고 registry `resolved` + sha512 `integrity`를 가질 때만(D-ADMIN-UI-002, [bundledEntryViolations]).
 * - `package.json`: dependencies·devDependencies·overrides가 정확한 버전(범위 금지), `engines.node` = catalog Node 버전.
 * - `overrides`는 보안 patch 예외만 허용한다 — `lockfile-policy.json`의 `securityOverrides`에 같은 버전·근거가 있어야 한다.
 * - override 대상 패키지는 lockfile 안에서 그 버전으로만 존재한다.
 * - `.npmrc`: 정확한 키 허용 목록·값만(모르는 키·중복 키·scope registry·인증 줄 거부).
 * - 한계: `lockfile-policy.json`의 `before`(14일 규칙)는 오프라인에서 강제하지 못한다 — 게시 시각은 registry `time`에만 있다.
 *   생성 절차(`frontendLockfileRegenerate`)가 지키고, 온라인 검사는 후속 항목이다(명세 §18).
 * 위반은 모두 모아 한 번에 실패한다.
 */
abstract class FrontendLockfileCheckTask : DefaultTask() {

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val packageJson: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val lockfile: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val npmrc: RegularFileProperty

    @get:InputFile @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val policy: RegularFileProperty

    @get:Input
    abstract val nodeVersion: Property<String>

    @get:OutputFile
    abstract val marker: RegularFileProperty

    @TaskAction
    fun check() {
        val violations = violations(
            packageJson = parse(packageJson.get().asFile.readText()),
            lock = parse(lockfile.get().asFile.readText()),
            npmrcLines = npmrc.get().asFile.readLines().map { it.trim() },
            policy = parse(policy.get().asFile.readText()),
            nodeVersion = nodeVersion.get(),
        )
        if (violations.isNotEmpty()) {
            throw GradleException("frontend supply-chain check failed:\n" + violations.joinToString("\n") { " - $it" })
        }
        marker.get().asFile.writeText("ok\n")
    }

    companion object {
        const val REGISTRY = "https://registry.npmjs.org/"
        private val EXACT_VERSION = Regex("""^\d+\.\d+\.\d+$""")
        val NPMRC_ALLOWED: Map<String, String> = linkedMapOf(
            "registry" to REGISTRY, "save-exact" to "true", "engine-strict" to "true", "ignore-scripts" to "true",
            "strict-ssl" to "true", "fund" to "false", "audit" to "false",
        )

        @Suppress("UNCHECKED_CAST")
        private fun parse(text: String): Map<String, Any?> = JsonSlurper().parseText(text) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        private fun Map<String, Any?>.obj(key: String): Map<String, Any?> = (this[key] as? Map<String, Any?>).orEmpty()

        fun violations(
            packageJson: Map<String, Any?>,
            lock: Map<String, Any?>,
            npmrcLines: List<String>,
            policy: Map<String, Any?>,
            nodeVersion: String,
        ): List<String> {
            val problems = mutableListOf<String>()
            if (lock["lockfileVersion"] != 3) problems += "package-lock.json lockfileVersion must be 3 (was ${lock["lockfileVersion"]})"

            val declared = listOf("dependencies", "devDependencies", "overrides")
            for (section in declared) {
                packageJson.obj(section).forEach { (name, version) ->
                    if (version !is String || !EXACT_VERSION.matches(version)) {
                        problems += "package.json $section.$name must be an exact version (was $version)"
                    }
                }
            }
            val engine = packageJson.obj("engines")["node"]
            if (engine != nodeVersion) problems += "package.json engines.node must be $nodeVersion (was $engine)"

            val packages = lock.obj("packages")
            val root = packages[""] as? Map<*, *> ?: emptyMap<String, Any?>()
            for (section in listOf("dependencies", "devDependencies")) {
                if ((root[section] ?: emptyMap<String, Any?>()) != packageJson.obj(section)) {
                    problems += "package-lock.json root $section differ from package.json (regenerate the lockfile)"
                }
            }
            var installed = 0
            packages.forEach { (path, value) ->
                if (path.isEmpty()) return@forEach
                val entry = value as? Map<*, *> ?: return@forEach
                if (entry["link"] == true) {
                    problems += "$path: linked packages are not allowed"
                    return@forEach
                }
                installed++
                if (entry["inBundle"] == true) {
                    // 리뷰 n-2: npm은 묶인 항목에 자기 resolved·integrity를 쓰지 않는다 — 있으면 손댄 lockfile로 보고 거부(fail closed).
                    if (entry.containsKey("resolved") || entry.containsKey("integrity")) {
                        problems += "$path: inBundle entry must not carry its own resolved or integrity (D-ADMIN-UI-002)"
                    }
                    problems += bundledEntryViolations(path, packages)
                    return@forEach
                }
                val resolved = entry["resolved"] as? String
                if (resolved == null || !resolved.startsWith(REGISTRY)) problems += "$path: resolved must start with $REGISTRY (was $resolved)"
                val integrity = entry["integrity"] as? String
                if (integrity == null || !integrity.startsWith("sha512-")) problems += "$path: integrity must be sha512 (was $integrity)"
            }
            if (installed == 0) problems += "package-lock.json has no installed packages"

            // override 대상은 lockfile 안에서 그 버전으로만 존재해야 한다(해석이 실제로 적용됐다는 증거 — S1 리뷰 m2).
            val installedVersions = packages.entries.filter { it.key.isNotEmpty() }.groupBy(
                { it.key.substringAfterLast("node_modules/") }, { (it.value as? Map<*, *>)?.get("version") },
            )
            packageJson.obj("overrides").forEach { (name, version) ->
                val found = installedVersions[name].orEmpty()
                if (found.isEmpty() || found.any { it != version }) {
                    problems += "package.json overrides.$name=$version is not the only resolved version in package-lock.json (found $found)"
                }
            }

            val allowedOverrides = policy.obj("securityOverrides")
            packageJson.obj("overrides").forEach { (name, version) ->
                val exception = allowedOverrides[name] as? Map<*, *>
                if (exception == null || exception["version"] != version || (exception["advisories"] as? List<*>).isNullOrEmpty()
                    || (exception["removeWhen"] as? String).isNullOrBlank()) {
                    problems += "package.json overrides.$name=$version needs a matching securityOverrides entry (version, advisories, removeWhen) in lockfile-policy.json"
                }
            }
            val before = policy["before"] as? String
            if (before == null || !Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""").matches(before)) {
                problems += "lockfile-policy.json before must be a UTC timestamp (was $before)"
            }
            problems += npmrcViolations(npmrcLines)
            return problems
        }

        /**
         * D-ADMIN-UI-002(Q-ADMIN-UI-1 판정 A): `inBundle: true` 항목(부모 tarball 안에 묶여 오는 bundleDependencies)은 자기 `resolved`·`integrity`가 없다.
         * 가장 가까운 부모 패키지 항목(경로의 마지막 `/node_modules/` 앞)이 (a) 그 이름을 `bundleDependencies`(또는 `bundledDependencies`)에 선언하고
         * (b) registry `resolved`와 sha512 `integrity`를 가질 때만 허용한다 — 묶인 파일은 부모 tarball 해시로 보호된다. 그 밖(부모 없음·미선언·부모 해시 없음·
         * registry 밖·부모도 묶인 항목)은 위반이다.
         */
        private fun bundledEntryViolations(path: String, packages: Map<String, Any?>): List<String> {
            val marker = "/node_modules/"
            val cut = path.lastIndexOf(marker)
            if (cut < 0) return listOf("$path: inBundle entry needs a parent package entry that bundles it (D-ADMIN-UI-002)")
            val parentPath = path.substring(0, cut)
            val name = path.substring(cut + marker.length)
            val parent = packages[parentPath] as? Map<*, *>
                ?: return listOf("$path: inBundle entry needs a parent package entry $parentPath that bundles it (D-ADMIN-UI-002)")
            val problems = mutableListOf<String>()
            // 이름 목록만 선언으로 본다 — `true`(전부 묶음) 같은 boolean은 어떤 이름도 선언하지 않은 것으로 처리(리뷰 n-2).
            val declared = ((parent["bundleDependencies"] ?: parent["bundledDependencies"]) as? List<*>).orEmpty()
            if (name !in declared) problems += "$path: parent $parentPath does not declare $name in bundleDependencies (D-ADMIN-UI-002)"
            val parentResolved = parent["resolved"] as? String
            val parentIntegrity = parent["integrity"] as? String
            if (parentResolved == null || !parentResolved.startsWith(REGISTRY) || parentIntegrity == null || !parentIntegrity.startsWith("sha512-")) {
                problems += "$path: parent $parentPath must have a registry resolved URL and a sha512 integrity (D-ADMIN-UI-002)"
            }
            return problems
        }

        /**
         * `.npmrc`는 정확한 키 허용 목록과 값만 허용한다(S1 리뷰 m1). 모르는 키(`script-shell` 등)·중복 키(뒤 줄이 앞 줄을 덮는다 —
         * 예: 두 번째 `registry=`, `strict-ssl=false` 추가)·scope registry(`@scope:registry=`)·인증 줄은 거부한다. 빈 줄·주석(#, ;)만 무시.
         */
        fun npmrcViolations(lines: List<String>): List<String> {
            val problems = mutableListOf<String>()
            val seen = mutableMapOf<String, String>()
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
                val key = line.substringBefore('=', missingDelimiterValue = "").trim()
                val value = line.substringAfter('=', missingDelimiterValue = "").trim()
                when {
                    key.isEmpty() -> problems += ".npmrc line is not key=value: '$line'"
                    key.startsWith("@") || key.startsWith("//") -> problems += ".npmrc scoped registry/auth line is not allowed: '$key'"
                    key !in NPMRC_ALLOWED -> problems += ".npmrc unknown key '$key'"
                    key in seen -> problems += ".npmrc duplicate key '$key'"
                    value != NPMRC_ALLOWED[key] -> problems += ".npmrc $key must be '${NPMRC_ALLOWED[key]}' (was '$value')"
                }
                if (key.isNotEmpty()) seen.putIfAbsent(key, value)
            }
            (NPMRC_ALLOWED.keys - seen.keys).forEach { problems += ".npmrc must set $it=${NPMRC_ALLOWED[it]}" }
            return problems
        }
    }
}
